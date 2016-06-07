
/**
 *
 * @author Osipov, OOO SKB Mayak, Russia, Nigny Novgorod, 2013
 */
public interface ehsDefines {
    public final static long SECOND              = 1000L;
    public final static int  WATCHDOG_TIMEOUT    = 180;
    public final static int  INPUT_BUFF_SIZE     = 2048;
    public final static int  OUTPUT_BUFF_SIZE    = 2048;
    public final static int  MAX_SOCKET_ERRORS   = 100;
    public final static int  MAX_APP_REG_TRIES   = 20;
    public final static int  MAX_APP_USSD_TRIES  = 20;
    public final static long APP_THREAD_DELAY    = 2L;
    public final static long APP_START_DELAY     = SECOND * 5L;
    public final static long APP_RECONNECT_DELAY = SECOND * 30L;
    public final static long APP_REG_REACH_DELAY = SECOND * 6L;
    public final static long APP_USSD_DELAY      = SECOND * 2L;
    public final static long APP_RX_TX_DELAY     = SECOND * 120L; //2 minutes
    public final static long APP_REG_REACH       = SECOND * 60L;  //1 minutes
    
    public final static long TIMEOUT_BEFORE_FW   = SECOND * 60L;
    public final static long TIMEOUT_AFTER_FW    = SECOND * 60L;
    public final static long TIMEOUT_WHOLE_FW    = SECOND * 600L; //10 minute for FW update from copy
    public final static String FW_FILE_PATH      = "file:///A:/fwupdate.bin";
    
    public final static int  GSM_NETWORK_MTS     = 1;
    public final static int  GSM_NETWORK_BEELINE = 2;
    public final static int  GSM_NETWORK_TELE2   = 3;
    public final static int  GSM_NETWORK_MEGAFON = 4;
    public final static int  GSM_NETWORK_ROSTLKM = 5;
    
    public final static String VERSION          = "fw 1.0.5";
    
    //for config
    public final static String PATH_CONFIG      = "file:///A:/config.dat";
    public final static String SETTINGS_HEADER  = "[USPD 2.01 GSM params v1.0]";
}
