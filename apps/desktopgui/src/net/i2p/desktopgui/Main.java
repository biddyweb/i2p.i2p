package net.i2p.desktopgui;

/*
 * Main.java
 */

import javax.swing.SwingUtilities;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientAppManager;
import net.i2p.app.ClientAppState;
import static net.i2p.app.ClientAppState.*;
import net.i2p.desktopgui.router.RouterManager;
import net.i2p.desktopgui.util.*;
import net.i2p.router.RouterContext;
import net.i2p.router.app.RouterApp;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;
import net.i2p.util.Translate;
import net.i2p.util.I2PProperties.I2PPropertyCallback;

/**
 * The main class of the application.
 */
public class Main implements RouterApp {

    private final I2PAppContext _appContext;
    private final RouterContext _context;
    private final ClientAppManager _mgr;
    private final Log log;
    private ClientAppState _state = UNINITIALIZED;
    private TrayManager _trayManager;
    public static final String PROP_ENABLE = "desktopgui.enabled";
    private static final String PROP_SWING = "desktopgui.swing";

    /**
     *  @since 0.9.26
     */
    public Main(RouterContext ctx, ClientAppManager mgr, String args[]) {
        _appContext = _context = ctx;
        _mgr = mgr;
        log = _appContext.logManager().getLog(Main.class);
        _state = INITIALIZED;
    }

    /**
     *  @since 0.9.26
     */
    public Main() {
        _appContext = I2PAppContext.getGlobalContext();
        if (_appContext instanceof RouterContext)
            _context = (RouterContext) _appContext;
        else
            _context = null;
        _mgr = null;
        log = _appContext.logManager().getLog(Main.class);
        _state = INITIALIZED;
    }
    
    /**
     * Start the tray icon code (loads tray icon in the tray area).
     * @throws AWTException on startup error, including systray not supported 
     */
    private synchronized void startUp() throws Exception {
        final TrayManager trayManager;
        boolean useSwingDefault = !(SystemVersion.isWindows() || SystemVersion.isMac());
        boolean useSwing = _appContext.getProperty(PROP_SWING, useSwingDefault);
        if (_context != null)
            trayManager = new InternalTrayManager(_context, this, useSwing);
        else
            trayManager = new ExternalTrayManager(_appContext, this, useSwing);
        trayManager.startManager();
        _trayManager = trayManager;
        changeState(RUNNING);
        if (_mgr != null)
            _mgr.register(this);
        
        if (_context != null) {
            _context.addPropertyCallback(new I2PPropertyCallback() {
                @Override
                public void propertyChanged(String arg0, String arg1) {
                    if(arg0.equals(Translate.PROP_LANG)) {
                        trayManager.languageChanged();
                    }
                }
            });
        }
    }
    
    public static void main(String[] args) {
        Main main = new Main();
        main.beginStartup(args);
    }

    /**
     * Main method launching the application.
     *
     * @param args unused
     */
    private void beginStartup(String[] args) {
        changeState(STARTING);
        String headless = System.getProperty("java.awt.headless");
        boolean isHeadless = Boolean.parseBoolean(headless);
        if (isHeadless) {
        	log.warn("Headless environment: not starting desktopgui!");
            changeState(START_FAILED, "Headless environment: not starting desktopgui!", null);
            return;
        }

        // TODO process args with getopt if needed
        
        if (_context == null)
            launchForeverLoop();
        //We'll be doing GUI work, so let's stay in the event dispatcher thread.
        SwingUtilities.invokeLater(new Runnable() {

            @Override
            public void run() {
                try {
                    startUp();
                } catch(Exception e) {
                    log.error("Failed while running desktopgui!", e);
                    changeState(START_FAILED, "Failed while running desktopgui!", e);
                }
                
            }
            
        });

    }
    
    /**
     * Avoids the app terminating because no Window is opened anymore.
     * More info: http://java.sun.com/javase/6/docs/api/java/awt/doc-files/AWTThreadIssues.html#Autoshutdown
     */
    private static void launchForeverLoop() {
       Runnable r = new Runnable() {
            public void run() {
                try {
                    Object o = new Object();
                    synchronized (o) {
                        o.wait();
                    }
                } catch (InterruptedException ie) {
                }
            }
        };
        Thread t = new Thread(r, "DesktopGUI spinner");
        t.setDaemon(false);
        t.start();
    }

    /////// ClientApp methods

    /** @since 0.9.26 */
    public synchronized void startup() {
        beginStartup(null);
    }

    /** @since 0.9.26 */
    public synchronized void shutdown(String[] args) {
        if (_state == STOPPED)
            return;
        changeState(STOPPING);
        if (_trayManager != null)
            _trayManager.stopManager();
        changeState(STOPPED);
    }

    /** @since 0.9.26 */
    public synchronized ClientAppState getState() {
        return _state;
    }

    /** @since 0.9.26 */
    public String getName() {
        return "desktopgui";
    }

    /** @since 0.9.26 */
    public String getDisplayName() {
        return "Desktop GUI";
    }

    /////// end ClientApp methods

    /** @since 0.9.26 */
    private void changeState(ClientAppState state) {
        changeState(state, null, null);
    }

    /** @since 0.9.26 */
    private synchronized void changeState(ClientAppState state, String msg, Exception e) {
        _state = state;
        if (_mgr != null)
            _mgr.notify(this, state, msg, e);
        if (_context == null) {
            if (msg != null)
                System.out.println(state + ": " + msg);
            if (e != null)
                e.printStackTrace();
        }
    }
}
