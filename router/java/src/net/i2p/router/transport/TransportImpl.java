package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Writer;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterIdentity;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.Job;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.LHMCache;
import net.i2p.util.Log;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SystemVersion;

/**
 * Defines a way to send a message to another peer and start listening for messages
 *
 */
public abstract class TransportImpl implements Transport {
    private final Log _log;
    private TransportEventListener _listener;
    protected final List<RouterAddress> _currentAddresses;
    // Only used by NTCP. SSU does not use. See send() below.
    private final BlockingQueue<OutNetMessage> _sendPool;
    protected final RouterContext _context;
    /** map from routerIdentHash to timestamp (Long) that the peer was last unreachable */
    private final Map<Hash, Long>  _unreachableEntries;
    private final Set<Hash> _wasUnreachableEntries;
    private final Set<InetAddress> _localAddresses;
    /** global router ident -> IP */
    private static final Map<Hash, byte[]> _IPMap;

    static {
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory == Long.MAX_VALUE)
            maxMemory = 96*1024*1024l;
        long min = 512;
        long max = 4096;
        // 1024 nominal for 128 MB
        int size = (int) Math.max(min, Math.min(max, 1 + (maxMemory / (128*1024))));
        _IPMap = new LHMCache(size);
    }

    /**
     * Initialize the new transport
     *
     */
    public TransportImpl(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(TransportImpl.class);

        _context.statManager().createRateStat("transport.sendMessageFailureLifetime", "How long the lifetime of messages that fail are?", "Transport", new long[] { 60*1000l, 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRequiredRateStat("transport.sendMessageSize", "Size of sent messages (bytes)", "Transport", new long[] { 60*1000l, 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRequiredRateStat("transport.receiveMessageSize", "Size of received messages (bytes)", "Transport", new long[] { 60*1000l, 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("transport.receiveMessageTime", "How long it takes to read a message?", "Transport", new long[] { 60*1000l, 5*60*1000l, 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("transport.receiveMessageTimeSlow", "How long it takes to read a message (when it takes more than a second)?", "Transport", new long[] { 60*1000l, 5*60*1000l, 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRequiredRateStat("transport.sendProcessingTime", "Time to process and send a message (ms)", "Transport", new long[] { 60*1000l, 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        //_context.statManager().createRateStat("transport.sendProcessingTime." + getStyle(), "Time to process and send a message (ms)", "Transport", new long[] { 60*1000l });
        _context.statManager().createRateStat("transport.expiredOnQueueLifetime", "How long a message that expires on our outbound queue is processed", "Transport", new long[] { 60*1000l, 10*60*1000l, 60*60*1000l, 24*60*60*1000l } );

        _currentAddresses = new CopyOnWriteArrayList();
        if (getStyle().equals("NTCP"))
            _sendPool = new ArrayBlockingQueue(8);
        else
            _sendPool = null;
        _unreachableEntries = new HashMap(16);
        _wasUnreachableEntries = new ConcurrentHashSet(16);
        _localAddresses = new ConcurrentHashSet(4);
        _context.simpleScheduler().addPeriodicEvent(new CleanupUnreachable(), 2 * UNREACHABLE_PERIOD, UNREACHABLE_PERIOD / 2);
    }

    /**
     * How many peers are we connected to?
     * For NTCP, this is the same as active,
     * but SSU actually looks at idle time for countActivePeers()
     */
    public int countPeers() { return countActivePeers(); }

    /**
     * How many peers active in the last few minutes?
     */
    public int countActivePeers() { return 0; }

    /**
     * How many peers are we actively sending messages to (this minute)
     */
    public int countActiveSendPeers() { return 0; }

    /** ...and 50/100/150/200/250 for BW Tiers K/L/M/N/O */
    private static final int MAX_CONNECTION_FACTOR = 50;

    /** Per-transport connection limit */
    public int getMaxConnections() {
        if (_context.commSystem().isDummy())
            // testing
            return 0;
        String style = getStyle();
        // object churn
        String maxProp;
        if (style.equals("SSU"))
            maxProp = "i2np.udp.maxConnections";
        else if (style.equals("NTCP"))
            maxProp = "i2np.ntcp.maxConnections";
        else // shouldn't happen
            maxProp = "i2np." + style.toLowerCase(Locale.US) + ".maxConnections";
        int def = MAX_CONNECTION_FACTOR;
        RouterInfo ri = _context.router().getRouterInfo();
        if (ri != null) {
            char bw = ri.getBandwidthTier().charAt(0);
            if (bw > Router.CAPABILITY_BW12 && bw <= Router.CAPABILITY_BW256)
                def *= (1 + bw - Router.CAPABILITY_BW12);
        }
        if (_context.netDb().floodfillEnabled()) {
            // && !SystemVersion.isWindows()) {
            def *= 17; def /= 10;  // 425 for Class O ff
        }
        // increase limit for SSU, for now
        if (style.equals("SSU"))
            //def = def * 3 / 2;
            def *= 3;
        return _context.getProperty(maxProp, def);
    }

    private static final int DEFAULT_CAPACITY_PCT = 75;

    /**
     * Can we initiate or accept a connection to another peer, saving some margin
     */
    public boolean haveCapacity() {
        return haveCapacity(DEFAULT_CAPACITY_PCT);
    }

    /** @param pct are we under x% 0-100 */
    public boolean haveCapacity(int pct) {
        return countPeers() < getMaxConnections() * pct / 100;
    }

    /**
     * Return our peer clock skews on a transport.
     * Vector composed of Long, each element representing a peer skew in seconds.
     * Dummy version. Transports override it.
     */
    public Vector getClockSkews() { return new Vector(); }

    public List<String> getMostRecentErrorMessages() { return Collections.EMPTY_LIST; }

    /**
     * Nonblocking call to pull the next outbound message
     * off the queue.
     *
     * Only used by NTCP. SSU does not call.
     *
     * @return the next message or null if none are available
     */
    protected OutNetMessage getNextMessage() {
        OutNetMessage msg = _sendPool.poll();
        if (msg != null)
            msg.beginSend();
        return msg;
    }

    /**
     * The transport is done sending this message
     *
     * @param msg message in question
     * @param sendSuccessful true if the peer received it
     */
    protected void afterSend(OutNetMessage msg, boolean sendSuccessful) {
        afterSend(msg, sendSuccessful, true, 0);
    }
    /**
     * The transport is done sending this message
     *
     * @param msg message in question
     * @param sendSuccessful true if the peer received it
     * @param allowRequeue true if we should try other transports if available
     */
    protected void afterSend(OutNetMessage msg, boolean sendSuccessful, boolean allowRequeue) {
        afterSend(msg, sendSuccessful, allowRequeue, 0);
    }
    /**
     * The transport is done sending this message
     *
     * @param msg message in question
     * @param sendSuccessful true if the peer received it
     * @param msToSend how long it took to transfer the data to the peer
     */
    protected void afterSend(OutNetMessage msg, boolean sendSuccessful, long msToSend) {
        afterSend(msg, sendSuccessful, true, msToSend);
    }
    /**
     * The transport is done sending this message.  This is the method that actually
     * does all of the cleanup - firing off jobs, requeueing, updating stats, etc.
     *
     * @param msg message in question
     * @param sendSuccessful true if the peer received it
     * @param msToSend how long it took to transfer the data to the peer
     * @param allowRequeue true if we should try other transports if available
     */
    protected void afterSend(OutNetMessage msg, boolean sendSuccessful, boolean allowRequeue, long msToSend) {
        boolean log = false;
        if (sendSuccessful)
            msg.timestamp("afterSend(successful)");
        else
            msg.timestamp("afterSend(failed)");

        if (!sendSuccessful)
            msg.transportFailed(getStyle());

        if (msToSend > 1000) {
            if (_log.shouldLog(Log.WARN))
                _log.warn(getStyle() + " afterSend slow: " + (sendSuccessful ? "success " : "FAIL ")
                          + msg.getMessageSize() + " byte "
                          + msg.getMessageType() + ' ' + msg.getMessageId() + " to "
                          + msg.getTarget().getIdentity().calculateHash().toBase64().substring(0,6) + " took " + msToSend + " ms");
        }
        //if (true)
        //    _log.error("(not error) I2NP message sent? " + sendSuccessful + " " + msg.getMessageId() + " after " + msToSend + "/" + msg.getTransmissionTime());

        long lifetime = msg.getLifetime();
        if (lifetime > 3000) {
            int level = Log.WARN;
            if (!sendSuccessful)
                level = Log.INFO;
            if (_log.shouldLog(level))
                _log.log(level, getStyle() + " afterSend slow (" + (sendSuccessful ? "success " : "FAIL ")
                          + lifetime + "/" + msToSend + "): " + msg.getMessageSize() + " byte "
                          + msg.getMessageType() + " " + msg.getMessageId() + " from " + _context.routerHash().toBase64().substring(0,6)
                          + " to " + msg.getTarget().getIdentity().calculateHash().toBase64().substring(0,6) + ": " + msg.toString());
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info(getStyle() + " afterSend: " + (sendSuccessful ? "success " : "FAIL ")
                          + msg.getMessageSize() + " byte "
                          + msg.getMessageType() + " " + msg.getMessageId() + " from " + _context.routerHash().toBase64().substring(0,6)
                          + " to " + msg.getTarget().getIdentity().calculateHash().toBase64().substring(0,6) + "\n" + msg.toString());
        }

        if (sendSuccessful) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getStyle() + " Sent " + msg.getMessageType() + " successfully to "
                           + msg.getTarget().getIdentity().getHash().toBase64());
            Job j = msg.getOnSendJob();
            if (j != null)
                _context.jobQueue().addJob(j);
            log = true;
            msg.discardData();
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info(getStyle() + " Failed to send " + msg.getMessageType()
                          + " to " + msg.getTarget().getIdentity().getHash().toBase64()
                          + " (details: " + msg + ')');
            if (msg.getExpiration() < _context.clock().now())
                _context.statManager().addRateData("transport.expiredOnQueueLifetime", lifetime);

            if (allowRequeue) {
                if ( ( (msg.getExpiration() <= 0) || (msg.getExpiration() > _context.clock().now()) )
                     && (msg.getMessage() != null) ) {
                    // this may not be the last transport available - keep going
                    _context.outNetMessagePool().add(msg);
                    // don't discard the data yet!
                } else {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("No more time left (" + new Date(msg.getExpiration())
                                  + ", expiring without sending successfully the "
                                  + msg.getMessageType());
                    if (msg.getOnFailedSendJob() != null)
                        _context.jobQueue().addJob(msg.getOnFailedSendJob());
                    MessageSelector selector = msg.getReplySelector();
                    if (selector != null) {
                        _context.messageRegistry().unregisterPending(msg);
                    }
                    log = true;
                    msg.discardData();
                }
            } else {
                MessageSelector selector = msg.getReplySelector();
                if (_log.shouldLog(Log.INFO))
                    _log.info("Failed and no requeue allowed for a "
                              + msg.getMessageSize() + " byte "
                              + msg.getMessageType() + " message with selector " + selector, new Exception("fail cause"));
                if (msg.getOnFailedSendJob() != null)
                    _context.jobQueue().addJob(msg.getOnFailedSendJob());
                if (msg.getOnFailedReplyJob() != null)
                    _context.jobQueue().addJob(msg.getOnFailedReplyJob());
                if (selector != null)
                    _context.messageRegistry().unregisterPending(msg);
                log = true;
                msg.discardData();
            }
        }

        if (log) {
            /*
            String type = msg.getMessageType();
            // the udp transport logs some further details
            _context.messageHistory().sendMessage(type, msg.getMessageId(),
                                                  msg.getExpiration(),
                                                  msg.getTarget().getIdentity().getHash(),
                                                  sendSuccessful);
             */
        }

        long now = _context.clock().now();
        long sendTime = now - msg.getSendBegin();
        long allTime = now - msg.getCreated();
        if (allTime > 5*1000) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Took too long from preperation to afterSend(ok? " + sendSuccessful
                          + "): " + allTime + "ms/" + sendTime + "ms after failing on: "
                          + msg.getFailedTransports() + " and succeeding on " + getStyle());
            if ( (allTime > 60*1000) && (sendSuccessful) ) {
                // WTF!!@#
                if (_log.shouldLog(Log.WARN))
                    _log.warn("WTF, more than a minute slow? " + msg.getMessageType()
                              + " of id " + msg.getMessageId() + " (send begin on "
                              + new Date(msg.getSendBegin()) + " / created on "
                              + new Date(msg.getCreated()) + "): " + msg);
                _context.messageHistory().messageProcessingError(msg.getMessageId(),
                                                                 msg.getMessageType(),
                                                                 "Took too long to send [" + allTime + "ms]");
            }
        }


        if (sendSuccessful) {
            // TODO fix this stat for SSU ticket #698
            _context.statManager().addRateData("transport.sendProcessingTime", lifetime);
            // object churn. 33 ms for NTCP and 788 for SSU, but meaningless due to
            // differences in how it's computed (immediate vs. round trip)
            //_context.statManager().addRateData("transport.sendProcessingTime." + getStyle(), lifetime, 0);
            _context.profileManager().messageSent(msg.getTarget().getIdentity().getHash(), getStyle(), sendTime, msg.getMessageSize());
            _context.statManager().addRateData("transport.sendMessageSize", msg.getMessageSize(), sendTime);
        } else {
            _context.profileManager().messageFailed(msg.getTarget().getIdentity().getHash(), getStyle());
            _context.statManager().addRateData("transport.sendMessageFailureLifetime", lifetime);
        }
    }

    /**
     * Asynchronously send the message as requested in the message and, if the
     * send is successful, queue up any msg.getOnSendJob job, and register it
     * with the OutboundMessageRegistry (if it has a reply selector).  If the
     * send fails, queue up any msg.getOnFailedSendJob
     *
     * Only used by NTCP. SSU overrides.
     *
     * Note that this adds to the queue and then takes it back off in the same thread,
     * so it actually blocks, and we don't need a big queue.
     *
     * TODO: Override in NTCP also and get rid of queue?
     */
    public void send(OutNetMessage msg) {
        if (msg.getTarget() == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error - bad message enqueued [target is null]: " + msg, new Exception("Added by"));
            return;
        }
        try {
            _sendPool.put(msg);
        } catch (InterruptedException ie) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Interrupted during send " + msg);
            return;
        }

        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Message added to send pool");
        //msg.timestamp("send on " + getStyle());
        outboundMessageReady();
        //if (_log.shouldLog(Log.INFO))
        //    _log.debug("OutboundMessageReady called");
    }
    /**
     * This message is called whenever a new message is added to the send pool,
     * and it should not block
     *
     * Only used by NTCP. SSU throws UOE.
     */
    protected abstract void outboundMessageReady();

    /**
     * Message received from the I2NPMessageReader - send it to the listener
     *
     * @param inMsg non-null
     * @param remoteIdent may be null
     * @param remoteIdentHash may be null, calculated from remoteIdent if null
     */
    public void messageReceived(I2NPMessage inMsg, RouterIdentity remoteIdent, Hash remoteIdentHash, long msToReceive, int bytesReceived) {
        //if (true)
        //    _log.error("(not error) I2NP message received: " + inMsg.getUniqueId() + " after " + msToReceive);

        int level = Log.INFO;
        if (msToReceive > 5000)
            level = Log.WARN;
        if (_log.shouldLog(level)) {
            StringBuilder buf = new StringBuilder(128);
            buf.append("Message received: ").append(inMsg.getClass().getName());
            buf.append(" / ").append(inMsg.getUniqueId());
            buf.append(" in ").append(msToReceive).append("ms containing ");
            buf.append(bytesReceived).append(" bytes ");
            buf.append(" from ");
            if (remoteIdentHash != null) {
                buf.append(remoteIdentHash.toBase64());
            } else if (remoteIdent != null) {
                buf.append(remoteIdent.getHash().toBase64());
            } else {
                buf.append("[unknown]");
            }
            buf.append(" and forwarding to listener: ");
            if (_listener != null)
                buf.append(_listener);

            _log.log(level, buf.toString());
        }

        if (remoteIdent != null)
            remoteIdentHash = remoteIdent.getHash();
        if (remoteIdentHash != null) {
            _context.profileManager().messageReceived(remoteIdentHash, getStyle(), msToReceive, bytesReceived);
            _context.statManager().addRateData("transport.receiveMessageSize", bytesReceived, msToReceive);
        }

        _context.statManager().addRateData("transport.receiveMessageTime", msToReceive);
        if (msToReceive > 1000) {
            _context.statManager().addRateData("transport.receiveMessageTimeSlow", msToReceive);
        }

        //// this functionality is built into the InNetMessagePool
        //String type = inMsg.getClass().getName();
        //MessageHistory.getInstance().receiveMessage(type, inMsg.getUniqueId(), inMsg.getMessageExpiration(), remoteIdentHash, true);

        if (_listener != null) {
            _listener.messageReceived(inMsg, remoteIdent, remoteIdentHash);
        } else {
            if (_log.shouldLog(Log.ERROR))
                _log.error("WTF! Null listener! this = " + toString(), new Exception("Null listener"));
        }
    }

    /** Do we increase the advertised cost when approaching conn limits? */
    protected static final boolean ADJUST_COST = true;
    protected static final int CONGESTION_COST_ADJUSTMENT = 2;

    /**
     *  What addresses are we currently listening to?
     *  Replaces getCurrentAddress()
     *  @return all addresses, non-null
     *  @since IPv6
     */
    public List<RouterAddress> getCurrentAddresses() {
        return _currentAddresses;
    }

    /**
     *  What address are we currently listening to?
     *  Replaces getCurrentAddress()
     *  @param ipv6 true for IPv6 only; false for IPv4 only
     *  @return first matching address or null
     *  @since IPv6
     */
    public RouterAddress getCurrentAddress(boolean ipv6) {
        for (RouterAddress ra : _currentAddresses) {
            if (ipv6 == TransportUtil.isIPv6(ra))
                return ra;
        }
        return null;
    }

    /**
     *  Do we have any current address?
     *  @since IPv6
     */
    public boolean hasCurrentAddress() {
        return !_currentAddresses.isEmpty();
    }

    /**
     * Ask the transport to update its address based on current information and return it
     * Transports should override.
     * @return all addresses, non-null
     * @since 0.7.12
     */
    public List<RouterAddress> updateAddress() {
        return _currentAddresses;
    }

    /**
     *  Replace any existing addresses for the current transport
     *  with the same IP length (4 or 16) with the given one.
     *  TODO: Allow multiple addresses of the same length.
     *  Calls listener.transportAddressChanged()
     *
     *  @param address null to remove all
     */
    protected void replaceAddress(RouterAddress address) {
        if (_log.shouldLog(Log.WARN))
             _log.warn("Replacing address with " + address, new Exception());
        if (address == null) {
            _currentAddresses.clear();
        } else {
            boolean isIPv6 = TransportUtil.isIPv6(address);
            for (RouterAddress ra : _currentAddresses) {
                if (isIPv6 == TransportUtil.isIPv6(ra))
                    _currentAddresses.remove(ra);
            }
            _currentAddresses.add(address);
        }
        if (_log.shouldLog(Log.WARN))
             _log.warn(getStyle() + " now has " + _currentAddresses.size() + " addresses");
        if (_listener != null)
            _listener.transportAddressChanged();
    }

    /**
     *  Save a local address we were notified about before we started.
     *
     *  @since IPv6
     */
    protected void saveLocalAddress(InetAddress address) {
        _localAddresses.add(address);
    }

    /**
     *  Return and then clear all saved local addresses.
     *
     *  @since IPv6
     */
    protected Collection<InetAddress> getSavedLocalAddresses() {
        List<InetAddress> rv = new ArrayList(_localAddresses);
        _localAddresses.clear();
        return rv;
    }

    /**
     *  Get all available address we can use,
     *  shuffled and then sorted by cost/preference.
     *  Lowest cost (most preferred) first.
     *  @return non-null, possibly empty
     *  @since IPv6
     */
    protected List<RouterAddress> getTargetAddresses(RouterInfo target) {
        List<RouterAddress> rv = target.getTargetAddresses(getStyle());
        // Shuffle so everybody doesn't use the first one
        if (rv.size() > 1) {
            Collections.shuffle(rv, _context.random());
            TransportUtil.IPv6Config config = getIPv6Config();
            int adj;
            switch (config) {
              case IPV6_DISABLED:
                adj = 10; break;
              case IPV6_NOT_PREFERRED:
                adj = 1; break;
              default:
              case IPV6_ENABLED:
                adj = 0; break;
              case IPV6_PREFERRED:
                adj = -1; break;
              case IPV6_ONLY:
                adj = -10; break;
            }
            Collections.sort(rv, new AddrComparator(adj));
        }
        return rv;
    }

    /**
     *  Compare based on published cost, adjusting for our IPv6 preference.
     *  Lowest cost (most preferred) first.
     *  @since IPv6
     */
    private static class AddrComparator implements Comparator<RouterAddress> {
        private final int adj;

        public AddrComparator(int ipv6Adjustment) {
            adj = ipv6Adjustment;
        }

        public int compare(RouterAddress l, RouterAddress r) {
            int lc = l.getCost();
            int rc = r.getCost();
            byte[] lip = l.getIP();
            byte[] rip = r.getIP();
            if (lip == null)
                lc += 20;
            else if (lip.length == 16)
                lc += adj;
            if (rip == null)
                rc += 20;
            else if (rip.length == 16)
                rc += adj;
            if (lc > rc)
                return 1;
            if (lc < rc)
                return -1;
            return 0;
        }
    }

    /**
     *  Notify a transport of an external address change.
     *  This may be from a local interface, UPnP, a config change, etc.
     *  This should not be called if the ip didn't change
     *  (from that source's point of view), or is a local address.
     *  May be called multiple times for IPv4 or IPv6.
     *  The transport should also do its own checking on whether to accept
     *  notifications from this source.
     *
     *  This can be called before startListening() to set an initial address,
     *  or after the transport is running.
     *
     *  This implementation does nothing. Transports should override if they want notification.
     *
     *  @param source defined in Transport.java
     *  @param ip typ. IPv4 or IPv6 non-local; may be null to indicate IPv4 failure or port info only
     *  @param port 0 for unknown or unchanged
     */
    public void externalAddressReceived(AddressSource source, byte[] ip, int port) {}

    /**
     *  Notify a transport of the results of trying to forward a port.
     *
     *  This implementation does nothing. Transports should override if they want notification.
     *
     *  @param ip may be null
     *  @param port the internal port
     *  @param externalPort the external port, which for now should always be the same as
     *                      the internal port if the forwarding was successful.
     */
    public void forwardPortStatus(byte[] ip, int port, int externalPort, boolean success, String reason) {}

    /**
     * What INTERNAL port would the transport like to have forwarded by UPnP.
     * This can't be passed via getCurrentAddress(), as we have to open the port
     * before we can publish the address, and that's the external port anyway.
     *
     * @return port or -1 for none or 0 for any
     */
    public int getRequestedPort() { return -1; }

    /** Who to notify on message availability */
    public void setListener(TransportEventListener listener) { _listener = listener; }
    /** Make this stuff pretty (only used in the old console) */
    public void renderStatusHTML(Writer out) throws IOException {}
    public void renderStatusHTML(Writer out, String urlBase, int sortFlags) throws IOException { renderStatusHTML(out); }

    public short getReachabilityStatus() { return CommSystemFacade.STATUS_UNKNOWN; }
    public void recheckReachability() {}
    public boolean isBacklogged(Hash dest) { return false; }
    public boolean isEstablished(Hash dest) { return false; }

    private static final long UNREACHABLE_PERIOD = 5*60*1000;

    public boolean isUnreachable(Hash peer) {
        long now = _context.clock().now();
        synchronized (_unreachableEntries) {
            Long when = _unreachableEntries.get(peer);
            if (when == null) return false;
            if (when.longValue() + UNREACHABLE_PERIOD < now) {
                _unreachableEntries.remove(peer);
                return false;
            } else {
                return true;
            }
        }
    }

    /** called when we can't reach a peer */
    /** This isn't very useful since it is cleared when they contact us */
    public void markUnreachable(Hash peer) {
        long now = _context.clock().now();
        synchronized (_unreachableEntries) {
            _unreachableEntries.put(peer, Long.valueOf(now));
        }
        markWasUnreachable(peer, true);
    }

    /** called when we establish a peer connection (outbound or inbound) */
    public void markReachable(Hash peer, boolean isInbound) {
        // if *some* transport can reach them, then we shouldn't banlist 'em
        _context.banlist().unbanlistRouter(peer);
        synchronized (_unreachableEntries) {
            _unreachableEntries.remove(peer);
        }
        if (!isInbound)
            markWasUnreachable(peer, false);
    }

    private class CleanupUnreachable implements SimpleTimer.TimedEvent {
        public void timeReached() {
            long now = _context.clock().now();
            synchronized (_unreachableEntries) {
                for (Iterator<Long> iter = _unreachableEntries.values().iterator(); iter.hasNext(); ) {
                    Long when = iter.next();
                    if (when.longValue() + UNREACHABLE_PERIOD < now)
                        iter.remove();
                }
            }
        }
    }

    /**
     * Was the peer UNreachable (outbound only) the last time we tried it?
     * This is NOT reset if the peer contacts us and it is never expired.
     */
    public boolean wasUnreachable(Hash peer) {
        if (_wasUnreachableEntries.contains(peer))
            return true;
        RouterInfo ri = _context.netDb().lookupRouterInfoLocally(peer);
        if (ri == null)
            return false;
        return null == ri.getTargetAddress(this.getStyle());
    }
    /**
     * Maintain the WasUnreachable list
     */
    public void markWasUnreachable(Hash peer, boolean yes) {
        if (yes)
            _wasUnreachableEntries.add(peer);
        else
            _wasUnreachableEntries.remove(peer);
        if (_log.shouldLog(Log.INFO))
            _log.info(this.getStyle() + " setting wasUnreachable to " + yes + " for " + peer,
                      yes ? new Exception() : null);
    }

    /**
     * IP of the peer from the last connection (in or out, any transport).
     *
     * @param IPv4 or IPv6, non-null
     */
    public void setIP(Hash peer, byte[] ip) {
        byte[] old;
        synchronized (_IPMap) {
            old = _IPMap.put(peer, ip);
        }
        if (!DataHelper.eq(old, ip))
            _context.commSystem().queueLookup(ip);
    }

    /**
     * IP of the peer from the last connection (in or out, any transport).
     *
     * @return IPv4 or IPv6 or null
     */
    public static byte[] getIP(Hash peer) {
        synchronized (_IPMap) {
            return _IPMap.get(peer);
        }
    }

    /**
     *  @since 0.9.3
     */
    static void clearCaches() {
        synchronized(_IPMap) {
            _IPMap.clear();
        }
    }

    /**
     *  @since IPv6
     */
    protected TransportUtil.IPv6Config getIPv6Config() {
        return TransportUtil.getIPv6Config(_context, getStyle());
    }

    /**
     *  Allows IPv6 only if the transport is configured for it.
     *  Caller must check if we actually have a public IPv6 address.
     *  @param addr non-null
     */
    protected boolean isPubliclyRoutable(byte addr[]) {
        return TransportUtil.isPubliclyRoutable(addr,
                                                getIPv6Config() != TransportUtil.IPv6Config.IPV6_DISABLED);
    }
}
