
import com.siemens.icm.io.file.FileConnection;
import java.io.DataOutputStream;
import java.util.Enumeration;
import javax.microedition.io.Connector;

/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

/**
 *
 * @author osipov
 */
public class ehsDebug {
    public static boolean USE_DEBUG = true;
    public static boolean DEBUG_TO_FILE = false;
    public static boolean DEBUG_TO_SYSTEMOUT = true;
    public static ehsDebug instance = null;
          
    private FileConnection fconn;
    private DataOutputStream fptr = null;
    
    private ehsDebug(){
        initInstance();
    }
    
    private void initInstance(){
        instance = this;
        if (USE_DEBUG){
            if (DEBUG_TO_FILE){
                initDebugFile();
            }
            
            if (DEBUG_TO_SYSTEMOUT){
                ehsDebug.Me().Out("DEBUG OUTPUT CREATED");
            }
        }
    }
    
    private void initDebugFile(){
        String debugFileDir = "file:///A:/";
        String nextDebugFileName = "file:///A:/debug_";
        int fileIndex = 0;
            
        try{
            //list debug files and found last
            FileConnection fconnlist = (FileConnection)Connector.open(debugFileDir);
            if (fconnlist.isDirectory()){
                Enumeration files = fconnlist.list("debug_*.log", true);
                while(files.hasMoreElements()){
                    try{
                        String fname = (String)files.nextElement();
                        int sIndex = fname.indexOf("_");
                        int eIndex = fname.indexOf(".log");
                        String pn = fname.substring(sIndex+1, eIndex);
                        int fIndex = Integer.parseInt(pn);
                        if (fIndex > fileIndex){
                            fileIndex = fIndex;
                        }
                    } catch (Exception e){
                    }
                }
            }
            
            //update last file index up to next one
            fileIndex = fileIndex + 1;
            nextDebugFileName = nextDebugFileName + fileIndex + ".log";
            
            //try to open new file
            fconn = (FileConnection)Connector.open(nextDebugFileName);
            if (!fconn.exists())
                fconn.create();
            
            fptr = fconn.openDataOutputStream();  
            
        } catch (Exception e){
            DEBUG_TO_FILE = false;
        }
    }
    
    private void addToDebugFile(String s){
        try{
            if (fptr != null){
                fptr.write(s.getBytes());
            }
        } catch (Exception e) {
        }
    }
    
    public static ehsDebug Me(){
        if (instance == null){
            instance = new ehsDebug();
        }
        
        return instance;
    }
    
    public void Out(String s){
        if (USE_DEBUG){
            if (DEBUG_TO_FILE){
                addToDebugFile(s+"\r\n");
            }
            
            if (DEBUG_TO_SYSTEMOUT){
                System.out.println(s);
            }
        }
    }
    
    public String char2hex(char value){
        int HEX1 = value/16;
        if (HEX1<=9) HEX1+='0';
        else HEX1=HEX1-10+'A';

        int HEX2=value%16;
        if (HEX2<=9) HEX2+='0';
        else HEX2=HEX2-10+'A';

        return "" + (char)HEX1 + "" + (char)HEX2;
    }
    
    public void OutBuff (String pre, byte[] buf, int size){
        String tmpStr = pre + " "+size+" bytes:";
        try {
            for (int i = 0; i < size; i++){
                tmpStr = tmpStr + " " + char2hex((char)(buf[i] & 0xFF));
            }

            System.out.println(tmpStr);
            
        } catch (Exception e){
        }
    }
}
