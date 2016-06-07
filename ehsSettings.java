

import com.siemens.icm.io.file.FileConnection;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.util.Vector;
import javax.microedition.io.Connector;


/**
 *
 * @author Osipov, OOO SKB Mayak, Russia, Nigny Novgorod, 2013
 */
public class ehsSettings implements ehsDefines, ehsErrorCodes{

    public static boolean USE_WDT = false;
    public static boolean online = false;
    
    //socket params (defaults)
    public static String serverIp = "212.67.9.44"; 
    public static int serverPort = 58599; 
    public static String providerApn1 = "";
    public static String providerApn2 = "";
    public static String providerLogin1 = "";
    public static String providerPassw1 = "";
    public static String providerLogin2 = "";
    public static String providerPassw2 = "";
    public static int simPinPresent = 3;
    public static String sim1Pin = "0000";
    public static String sim2Pin = "0000";
    public static boolean useSecondSim = false;
    public static boolean useGprs = true;

    public static int providerTimeout = 120;
    public static String operatorName = "ERROR";
    public static int operatorIndex = -1;
    public static String modemImei = "ERROR";
    public static int lastErrorCode = ERR_CODE_NO_ERRORS;

    //port params
    public static int portNumber = 0;
    public static int portBaud = 115200;

    //load saved setings in NVM
    public boolean loadSettings(){
        boolean result = false;
        
        ehsDebug.Me().Out("create buffer for config");
        byte buffer[] = new byte[45000];
        StringBuffer sb = new StringBuffer();
        ehsDebug.Me().Out("create buffer for config done");
        int dataSize = 0;
        
        FileConnection fc;
        
        try{
            fc = (FileConnection)Connector.open(PATH_CONFIG);
            if (fc.exists()) {
                DataInputStream is = fc.openDataInputStream();
                               
                ehsDebug.Me().Out("config read");
                dataSize = is.read(buffer);
                ehsDebug.Me().Out("config read (" +dataSize+" bytes) done");
                
                ehsDebug.Me().Out("config conver to string");
                for (int i = 0; i < dataSize; i++){
                    sb.append((char)buffer[i]);
                }
                ehsDebug.Me().Out("config conver to string done");                              
                is.close();
                is = null;
                result = true;
                
                //parse config
                Vector configLines = new Vector();
                int parsedItems = splitToVector(sb.toString(), configLines, "\r\n");
                if (parsedItems > 1){
                    ehsDebug.Me().Out("config contain more than 1 line, try to parse");
                    String line = (String)(configLines.elementAt(0));
                    if (line.compareTo(SETTINGS_HEADER) == 0){
                        ehsDebug.Me().Out("config header found, parse other lines");
                        for(int lineIndex = 1; lineIndex < parsedItems; lineIndex++){
                            line = (String)(configLines.elementAt(lineIndex));
                            if (line!=null){
                                ehsDebug.Me().Out("config line [" + lineIndex + "] have data ["+line + "]" );
                            } else {
                                ehsDebug.Me().Out("config line [" + lineIndex + "] have no data (null String) ");
                                line = "";
                            }
                                       
                            switch (lineIndex){
                                case 1: //mode
                                    try{
                                        if(Integer.parseInt(line) == 1){
                                            useGprs = true;
                                        } else {
                                            useGprs = false;
                                        }
                                    } catch (Exception e){
                                    }
                                    break;
                                    
                                case 2: //server
                                    serverIp = line;
                                    break;
                                    
                                case 3: //port
                                    try{
                                        serverPort = Integer.parseInt(line);
                                    } catch (Exception e){
                                    }
                                    break;
                                    
                                case 4: //apn1
                                    providerApn1 = line;
                                    break;
                                    
                                case 5: //login1
                                    providerLogin1 = line;
                                    break;
                                    
                                case 6: //password1
                                    providerPassw1 = line;
                                    break;
                                    
                                case 7: //apn2
                                    providerApn2 = line;
                                    break;
                                    
                                case 8: //login2
                                    providerLogin2 = line;
                                    break;
                                    
                                case 9: //password2
                                    providerPassw2 = line;
                                    break;
                                    
                                case 10: //sim pin presence
                                    try{
                                        simPinPresent = Integer.parseInt(line);
                                    } catch (Exception e){
                                    }
                                    break;
                                    
                                case 11: //sim pin1
                                    sim1Pin=line;
                                    break;
                                    
                                case 12: //sim pin2
                                    sim2Pin=line;
                                    break;
                                    
                                case 13: //use second sim
                                    try{
                                        if(Integer.parseInt(line) == 1){
                                            useSecondSim = true;
                                        } else {
                                            useSecondSim = false;
                                        }
                                    } catch (Exception e){
                                    }
                                    break;
                                    
                                case 14: //last error
                                     try{
                                        lastErrorCode = Integer.parseInt(line);
                                    } catch (Exception e){
                                    }
                                    break;
                            }
                        }
                    } else {
                        ehsDebug.Me().Out("config inconsisted, skip parsing, try use default for network");
                    }
                
                } else {
                    ehsDebug.Me().Out("config inconsisted, skip parsing, try use default for network");  
                }
              
                                
            } else {
                
                ehsDebug.Me().Out("no config found");
            }
            
            fc.close();
                   
        } catch (Exception e){
            ehsDebug.Me().Out("ERROR / can't read config from FS "+e.toString());
        } catch (Error er) {
            ehsDebug.Me().Out("ERROR / can't read config from FS "+er.toString());
        }
        
        fc = null;
       
        return result;
    }

    //store setings in NVM
    public void saveSettings(){
        
        String settingsData = SETTINGS_HEADER+"\r\n";
        
        //glue settings data
        if (useGprs){
            settingsData +="1"+"\r\n";
        }else{
            settingsData +="0"+"\r\n";
        }
        settingsData +=serverIp+"\r\n";
        settingsData +=serverPort+"\r\n";
        
        settingsData +=providerApn1+"\r\n";
        settingsData +=providerLogin1+"\r\n";
        settingsData +=providerPassw1+"\r\n";
        
        settingsData +=providerApn2+"\r\n";
        settingsData +=providerLogin2+"\r\n";
        settingsData +=providerPassw2+"\r\n";
        
        settingsData +=simPinPresent+"\r\n";
        settingsData +=sim1Pin+"\r\n";
        settingsData +=sim2Pin+"\r\n";
        
        if (useSecondSim){
            settingsData +="1"+"\r\n";
        }else{
            settingsData +="0"+"\r\n";
        }
        
        settingsData +=lastErrorCode+"\r\n";
        
        FileConnection fc;
                
        try{
            fc = (FileConnection)Connector.open(PATH_CONFIG);
            
            if (fc.exists())
                fc.delete();
            
            fc.create();
            
            OutputStream os = fc.openOutputStream();
                       
            //write data
	    os.write(settingsData.getBytes());
            
            os.close();
            os = null;
            
            fc.close();
            
        } catch (Exception e){
            ehsDebug.Me().Out("ERROR / can't store config to FS "+e.toString());
	    deleteConfig();
	    
        } finally {
            fc = null;
        }
    }
    
     public void deleteConfig(){
        FileConnection fc;
        
        try{
            
            fc = (FileConnection)Connector.open(PATH_CONFIG);
            if (fc.exists())
                fc.delete();
            
	    fc.close();
	    
        } catch (Exception e){
            ehsDebug.Me().Out("ERROR / can't delete config from FS "+e.toString());
            
        } finally {	    
            fc = null;
        }
    }
     
    public int splitToVector(String input, Vector output, String delim){
        int pos = 0;
        int prevPos = 0;
        
        output.removeAllElements();
        
        try{
            while(true){
                prevPos = pos;
                pos = input.indexOf(delim, pos);
                if (pos!=-1){
                    String line = input.substring(prevPos, pos);
                    
                    output.addElement(line);
                    pos+=delim.length();
                    if (pos >= input.length()){ //finish
                        break;
                    }
                } else {
                    //append last or one item
                    output.addElement(input.substring(prevPos, input.length()));
                    break;
                }
            }
        } catch (Exception e){
             ehsDebug.Me().Out("ERROR / PARSER / jParser::parse() " + e.getMessage());
        }
        
        return output.size();
    }

    public void setLastError(int code){
        lastErrorCode = code;
        saveSettings();
    }

    public void setServerAndPort(String server, int port){
        serverIp = server;
        serverPort = port;
        saveSettings();
    }

    public void setApn1(String apn1){
        providerApn1 = apn1;
        saveSettings();
    }

    public void setApn2(String apn2){
        providerApn2 = apn2;
        saveSettings();
    }

    public void setLP1(String l1, String p1){
        providerLogin1 = l1;
        providerPassw1 = p1;
        saveSettings();
    }

    public void setLP2(String l2, String p2){
        providerLogin2 = l2;
        providerPassw2 = p2;
        saveSettings();
    }

    public void setSim1Pin(String s1p){
        if (s1p.toUpperCase().equals("NO SET")) {
            sim1Pin = "";
            simPinPresent = ((simPinPresent | 1) - 1);
            
        } else {
            sim1Pin = s1p;
            simPinPresent = (simPinPresent | 1);
        }
        saveSettings();
    }

    public void setSim2Pin(String s2p){
        if (s2p.toUpperCase().equals("NO SET")) {
            sim2Pin = "";
            simPinPresent = ((simPinPresent | 2) - 2);

        } else {
            sim2Pin = s2p;
            simPinPresent = (simPinPresent | 2);
        }
        saveSettings();
    }

    public void setSim2UseFlag(boolean canUse){
        useSecondSim = canUse;
        saveSettings();
    }

    public void setUseGprsConnection(boolean canUse){
        useGprs = canUse;
        saveSettings();
    }

    public boolean setDefaultSettings(int simIndex){

        //mts
        if (operatorName.toUpperCase().indexOf("MTS")!=-1){
            operatorIndex = GSM_NETWORK_MTS;
            return checkMts(simIndex);
        } else if (operatorName.toUpperCase().indexOf("MTS-RUS")!=-1){
            operatorIndex = GSM_NETWORK_MTS;
            return checkMts(simIndex);
        } else if (operatorName.toUpperCase().indexOf("RUS-MTS")!=-1){
            operatorIndex = GSM_NETWORK_MTS;
            return checkMts(simIndex);
        } else if (operatorName.toUpperCase().indexOf("RUS-01")!=-1){
            operatorIndex = GSM_NETWORK_MTS;
            return checkMts(simIndex);

        //beeline
        } else if (operatorName.toUpperCase().indexOf("BEELINE")!=-1){
            operatorIndex = GSM_NETWORK_BEELINE;
            return checkBeeline(simIndex);
        } else if (operatorName.toUpperCase().indexOf("RUS-99")!=-1){
            operatorIndex = GSM_NETWORK_BEELINE;
            return checkBeeline(simIndex);

        //megafone
        } else if (operatorName.toUpperCase().indexOf("MEGAFON")!=-1){
            operatorIndex = GSM_NETWORK_MEGAFON;
            return checkMegafone(simIndex);

        //tele-2
        } else if (operatorName.toUpperCase().indexOf("TELE2")!=-1){
            operatorIndex = GSM_NETWORK_TELE2;
            return checkTele2(simIndex);
        } else if (operatorName.toUpperCase().indexOf("MOTIV")!=-1){
            operatorIndex = GSM_NETWORK_TELE2;
            return checkTele2(simIndex);
        } else if (operatorName.toUpperCase().indexOf("25020")!=-1){
            operatorIndex = GSM_NETWORK_TELE2;
            return checkTele2(simIndex);

        //rostelekom
        } else if (operatorName.toUpperCase().indexOf("ROSTELECOM")!=-1){
            operatorIndex = GSM_NETWORK_ROSTLKM;
            return checkRosTelekom(simIndex);
        } else if (operatorName.toUpperCase().indexOf("UTEL")!=-1){
            operatorIndex = GSM_NETWORK_ROSTLKM;
            return checkRosTelekom(simIndex);
        } else if (operatorName.toUpperCase().indexOf("URALTEL")!=-1){
            operatorIndex = GSM_NETWORK_ROSTLKM;
            return checkRosTelekom(simIndex);
        } else if (operatorName.toUpperCase().indexOf("RUS-39")!=-1){
            operatorIndex = GSM_NETWORK_ROSTLKM;
            return checkRosTelekom(simIndex);
        } else if (operatorName.toUpperCase().indexOf("NCC")!=-1){
            operatorIndex = GSM_NETWORK_ROSTLKM;
            return checkRosTelekom(simIndex);
        } else if (operatorName.toUpperCase().indexOf("RUS-03")!=-1){
            operatorIndex = GSM_NETWORK_ROSTLKM;
            return checkRosTelekom(simIndex);
        } else if (operatorName.toUpperCase().indexOf("CC 250 NC 03")!=-1){
            operatorIndex = GSM_NETWORK_ROSTLKM;
            return checkRosTelekom(simIndex);
        }

        return false;
    }

    public boolean simPinPresentForSim(int simIndex){
        switch (simIndex){
            case 1: return (((simPinPresent & (byte)1) == 1) && (sim1Pin.length()==4));
            case 2: return (((simPinPresent & (byte)2) == 2) && (sim2Pin.length()==4));
        }

        return false;
    }

    private boolean checkMts(int simIndex){
        boolean ch = false;
        if (simIndex == 1){
            if (providerApn1.length()==0){
                providerApn1 = "internet.mts.ru";
                providerLogin1 = "mts";
                providerPassw1 = "mts";
                ch = true;
            }
        } else {
            if (providerApn2.length()==0){
                providerApn2 = "internet.mts.ru";
                providerLogin2 = "mts";
                providerPassw2 = "mts";
                ch = true;
            }
        }

        //save
        if (ch)
            saveSettings();
        
        return ch;
    }

    private boolean checkBeeline(int simIndex){

        boolean ch = false;
        if (simIndex == 1){
            if (providerApn1.length()==0){
                providerApn1 = "internet.beeline.ru";
                 providerLogin1 = "beeline";
                 providerPassw1 = "beeline";
                ch = true;
            }
        } else {
            if (providerApn2.length()==0){
                providerApn2 = "internet.beeline.ru";
                providerLogin2 = "beeline";
                providerPassw2 = "beeline";
                ch = true;
            }
        }

        //save
        if (ch)
            saveSettings();

        return ch;
    }

    private boolean checkMegafone(int simIndex){

        boolean ch = false;
        if (simIndex == 1){
            if (providerApn1.length()==0){
                providerApn1 = "internet";
                providerLogin1 = "";
                providerPassw1 = "";
                ch = true;
            }
        } else {
            if (providerApn2.length()==0){
                providerApn2 = "internet";
                providerLogin2 = "";
                providerPassw2 = "";
                ch = true;
            }
        }
        //save
        if (ch)
            saveSettings();

        return ch;
    }

    private boolean checkTele2(int simIndex){

        boolean ch = false;

        if (simIndex == 1){
            if (providerApn1.length()==0){
                providerApn1 = "internet.tele2.ru";
                providerLogin1 = "";
                providerPassw1 = "";
                ch = true;
            }
        } else {
            if (providerApn2.length()==0){
                providerApn2 = "internet.tele2.ru";
                providerLogin2 = "";
                providerPassw2 = "";
                ch = true;
            }
        }

        //save
        if (ch)
            saveSettings();

        return ch;
    }

    private boolean checkRosTelekom(int simIndex){

        boolean ch = false;

        if (simIndex == 1){
            if (providerApn1.length()==0){
                providerApn1 = "internet";
                providerLogin1 = "";
                providerPassw1 = "";
                ch = true;
            }
        } else {
            if (providerApn2.length()==0){
                providerApn2 = "internet";
                providerLogin2 = "";
                providerPassw2 = "";
                ch = true;
            }
        }

        //save
        if (ch)
            saveSettings();

        return ch;
    }
}
