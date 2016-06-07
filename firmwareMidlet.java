
import com.siemens.icm.io.file.FileConnection;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import javax.microedition.io.CommConnection;
import javax.microedition.io.Connector;
import javax.microedition.io.SocketConnection;
import javax.microedition.midlet.*;

/**
 * @author Osipov, OOO SKB Mayak, Russia, Nigny Novgorod, 2013
 */
public class firmwareMidlet extends MIDlet implements ehsDefines, ehsErrorCodes {
    //modem settigns, at part, sms cathcer and watchdog
    private ehsSettings settings;
    private ehsAtInterpreter atint;

    //socket objects
    private SocketConnection SOCK = null;
    private DataInputStream SOCK_I = null;
    private DataOutputStream SOCK_O = null;

    //port objects
    private CommConnection PORT = null;
    private DataInputStream PORT_I = null;
    private DataOutputStream PORT_O = null;

    //buffers
    private byte inputBuffer[] = new byte[INPUT_BUFF_SIZE];
    private byte outputBuffer[] = new byte[OUTPUT_BUFF_SIZE];
    
    private int recvPacketSize = 0;
    private int recvPacketPayloadSize = 0;
    private byte recvPacket[] = new byte[INPUT_BUFF_SIZE];
    private int parsePacketState = 0;
    
    //flags
    private boolean ready = false;
    private boolean fwFlag = false;
    private int fwRecvState = 0;
    private int fwSendState = 0;
    private long fwRecvOffset = 0;
    private long fwSendOffset = 0;
    
    //update file
    private FileConnection fwfc;
    private DataOutputStream fwfcdo;
    private DataInputStream fwfcdi;
    
    //test stubs
    private boolean skipCrc = true;
    
    //error counter
    int connectionErrorsCounter = 0;
    long lastAvailableBytesFromInput = 0L;
    long lastTimeRegCheck = System.currentTimeMillis();
    
    private boolean isTimeToCheckReg(){
        long nowTime = System.currentTimeMillis();
        if ((nowTime - lastTimeRegCheck) > APP_REG_REACH){
            lastTimeRegCheck = System.currentTimeMillis();
            return true;
        }
        
        return false;
    }
    
    private void resetUpdateFlags(){
        fwFlag = false;
        fwRecvOffset = 0;
        fwRecvState = 0;
        fwSendOffset = 0;
        fwSendState = 0;
    }
    
    private boolean doGprsLoop(){
        boolean result = false;
        
        try{
            //open connections to gprs and ASC0
            openSocket();
            
            //fix time span
            lastAvailableBytesFromInput = System.currentTimeMillis();
            
            if ((settings.online) && (ready)){
                //formConnectedPacket(false);
                resetUpdateFlags();
            }
            
            //while connections is online and port ready
            while ((settings.online) && (ready) && (settings.useGprs)){
                transmitGprs();
                Thread.sleep(APP_THREAD_DELAY);
                
                //close GPRS loop while send update to 7188
                if (fwSendState > 0){
                    ehsDebug.Me().Out("UPDATE: GO TO SEND FW TO 7188");
                    connectionErrorsCounter = 0;
                    break;
                }
            }

            //if connection was aborted increment error counter
            connectionErrorsCounter++;

            //close
            closeSocket();

            //if error counter reached MAX_SOCKET_ERRORS, exit loop, reset by wdt and reinit module
            if (connectionErrorsCounter >= MAX_SOCKET_ERRORS){
                ehsDebug.Me().Out("ERROR in gprs loop: to much connection errors, quit loop");
                result = true;
            }

        } catch (Exception e){
            ehsDebug.Me().Out("ERROR in gprs loop: " + e.toString());
            result = true;
        }

        return result;
    }

    private boolean doCsdLoop(){
        boolean result = false;

        try{
            //open connections to gprs and ASC0
            if (atint.csdIsEstablished){
                
                //fix time span
                lastAvailableBytesFromInput = System.currentTimeMillis();
                
                if ((atint.csdIsEstablished) && (ready)){
                    //formConnectedPacket(false);
                    resetUpdateFlags();
                }
                
                //while connections is online and port ready
                while ((atint.csdIsEstablished) && (ready) && (settings.useGprs == false)){
                    transmitCsd();
                    Thread.sleep(APP_THREAD_DELAY);
                    
                    //close GPRS loop while send update to 7188
                    if (fwSendState > 0){
                        connectionErrorsCounter = 0;
                        break;
                    }
                }
                
                atint.hangupCall();
                
            } else {
                
                if(isTimeToCheckReg()){
                    //check operator changed
                    String prevOperator = settings.operatorName;
                    
                    if (atint.getOperatorName()){
                        if (prevOperator.compareTo(settings.operatorName) != 0){
                            ehsDebug.Me().Out("operator changed, reboot ");
                            atint.perfomeReboot();
                        }
                    }
                    
                    //check no reg
                    if (!atint.isNetRegistrationDone()){
                        ehsDebug.Me().Out("ERROR on csd listen: no reg in network, so reboot ");
                        atint.perfomeReboot();
                    }
                }
            }
            
        } catch (Exception e){
            ehsDebug.Me().Out("ERROR in csd loop: " + e.toString());
            result = true;
        }

        return result;
    }

    //main
    public void startApp() {
        boolean result = false; //false is error
        boolean needQuit = false;

        //try to sleep after start to allow moded system done some initialization
        try{
            Thread.sleep(APP_START_DELAY);
        } catch (Exception e){}

        //try to init modem
        result = initModem();
        if (result){
            //goto transmition cycle
            while (true){
                try{
                    
                    // need to send update
                    if (fwSendState > 0) {
                       ehsDebug.Me().Out("UPDATE: GO!");
                       processUpdate();
                       
                    } else {
                        
                        //depending on settings
                        if (settings.useGprs){
                            needQuit = doGprsLoop();

                            //sleep between reconnects on 30 seconds
                            Thread.sleep(APP_RECONNECT_DELAY);

                        } else {
                            needQuit = doCsdLoop();

                            //sleep between checks of incoming CSD call
                            Thread.sleep(APP_THREAD_DELAY);
                        }

                        //check result
                        if (needQuit)
                            break;
                    }

                } catch (Exception e){
                    ehsDebug.Me().Out("ERROR in main loop: " + e.toString());
                    settings.setLastError(11);// errors in main loop
                }
            }
        }

        atint.perfomeReboot();
        ehsDebug.Me().Out("main loop was quited, modem will be rebooted with watchdog" );
        
        //if we leave main cycle (or skip them execution) quit application to restart them
        notifyDestroyed();

        //simple exit
        //no operations more
    }

    public void pauseApp() {
        ehsDebug.Me().Out("Application paused");
    }

    public void destroyApp(boolean unconditional) {
        closePort();
        closeSocket();
        
        ehsDebug.Me().Out("Application destroyed");
    }

    private void openPort(){
         try{
            PORT = (CommConnection)Connector.open(getPortParams());
            PORT_I = PORT.openDataInputStream();
            PORT_O = PORT.openDataOutputStream();
            ready = true;
        } catch (Exception e){
            ready = false;
            ehsDebug.Me().Out("ERROR on openPort: " + e.toString());
            settings.setLastError(ERR_CODE_OPEN_ASC);// unable to open ASC
        }
    }

    private void openSocket(){
        try{
            //try to open
            SOCK = (SocketConnection)Connector.open(getSocketParams());
            SOCK_I = SOCK.openDataInputStream();
            SOCK_O = SOCK.openDataOutputStream();

            //set keep alive
            SOCK.setSocketOption(SOCK.KEEPALIVE, 10);

            //reset errors counter and setup online flag
            connectionErrorsCounter = 0;
            settings.online = true;
            
            ehsDebug.Me().Out("CONNECTION: OPEN");
            
        } catch (Exception e){
            settings.online = false;
            ehsDebug.Me().Out("ERROR on openSocket: " + e.toString());
            settings.setLastError(ERR_CODE_OPEN_SCOKET);// unable to open SCOKET
            
            //check operator changed
            String prevOperator = settings.operatorName;
                    
            if (atint.getOperatorName()){
                if (prevOperator.compareTo(settings.operatorName) != 0){
                    ehsDebug.Me().Out("operator changed, reboot ");
                    atint.perfomeReboot();
                }
            }
            
            //check no reg
            if (!atint.isNetRegistrationDone()){
                ehsDebug.Me().Out("ERROR on openSocket: no reg in network, so reboot ");
                atint.perfomeReboot();
            }
        }
    }
    
    private void requestUpdatePart(boolean toCsd){
        byte fwreq[] = new byte[27];
        
        ehsDebug.Me().Out("HANDLE INPUT: Ask part of FW from " + fwRecvOffset);
        
        try {
            fwreq[0] = (byte)0x7f;
            fwreq[1] = (byte)settings.modemImei.charAt(0);
            fwreq[2] = (byte)settings.modemImei.charAt(1);
            fwreq[3] = (byte)settings.modemImei.charAt(2);
            fwreq[4] = (byte)settings.modemImei.charAt(3);
            fwreq[5] = (byte)settings.modemImei.charAt(4);
            fwreq[6] = (byte)settings.modemImei.charAt(5);
            fwreq[7] = (byte)settings.modemImei.charAt(6);
            fwreq[8] = (byte)settings.modemImei.charAt(7);
            fwreq[9] = (byte)settings.modemImei.charAt(8);
            fwreq[10]= (byte)settings.modemImei.charAt(9);
            fwreq[11]= (byte)settings.modemImei.charAt(10);
            fwreq[12]= (byte)settings.modemImei.charAt(11);
            fwreq[13]= (byte)settings.modemImei.charAt(12);
            fwreq[14]= (byte)settings.modemImei.charAt(13);
            fwreq[15]= (byte)settings.modemImei.charAt(14);
            fwreq[16]= (byte)0x0; //cmd
            fwreq[17]= (byte)0xC7; //cmd
            fwreq[18]= (byte)0x0; //flags
            fwreq[19]= (byte)0x0; //flags
            fwreq[20]= (byte)0x0; //size
            fwreq[21]= (byte)0x4; //size
            fwreq[22]= (byte)((fwRecvOffset >> 24) & 0xFF);
            fwreq[23]= (byte)((fwRecvOffset >> 16) & 0xFF);
            fwreq[24]= (byte)((fwRecvOffset >> 8) & 0xFF);
            fwreq[25]= (byte)(fwRecvOffset & 0xFF);
            fwreq[26]= (byte)calcCrcForBuffer(fwreq, 26);
            
            if (toCsd){
                atint.CSD_O.write(fwreq, 0, 27);
            } else {
                SOCK_O.write(fwreq, 0, 27);
            }
            
        } catch (Exception e){
        
        }
        
        fwreq = null;
    }
    
    private void processUpdate(){
        long updateStarted = 0L;
        long partSize = 0L;
        
        try{
            while (true){
                switch (fwSendState){
                    case 1: //reset
                        ehsDebug.Me().Out("UPDATE: first step");
                        updateStarted = System.currentTimeMillis();
                        fwSendState = 2;
                        ehsDebug.Me().Out("UPDATE: wait 1 minute");
                        break;

                    case 2: //wait 1 minute
                        if ((System.currentTimeMillis() - updateStarted) > TIMEOUT_BEFORE_FW){
                            ehsDebug.Me().Out("UPDATE: wait 1 minute done");
                            fwSendState = 3;
                        } else {
                            Sleep(SECOND*10);
                        }
                        break;

                    case 3: //start
                        ehsDebug.Me().Out("UPDATE: form connected indication");
                        formConnectedPacket(true);
                        Sleep(SECOND);
                        fwSendState = 4;
                        break;

                    case 4: //wait in com port ask about fw update
                        ehsDebug.Me().Out("UPDATE: open BIN");
                        openUpdateCopy('r');
                        fwSendState = 5;
                        break;

                    case 5: //portions 0... N
                        fwSendOffset = recvUpdateReqFrom7188();
                        if (fwSendOffset >= 0L){
                            ehsDebug.Me().Out("UPDATE: send BIN from " + fwSendOffset);
                            partSize = sendUpdateFilePart();
                            ehsDebug.Me().Out("UPDATE: send BIN part size="+partSize);
                            if (partSize == 0L) {
                                fwSendState = 6;
                            }
                        }
                        break;

                    case 6: //done, delete file, close
                        fwSendState = 7;
                        ehsDebug.Me().Out("UPDATE: close, del BIN");
                        closeUpdateCopy();
                        deleteUpdateCopy();
                        updateStarted = System.currentTimeMillis();
                        ehsDebug.Me().Out("UPDATE: wait 1 minute");
                        break;

                    case 7: //wait 1 minute after update
                        if ((System.currentTimeMillis() - updateStarted) > TIMEOUT_AFTER_FW){
                            fwSendState = 8;
                            ehsDebug.Me().Out("UPDATE: wait 1 minute done");
                        } else {
                            Sleep(SECOND);
                        }
                        break;

                    case 8://quit
                        ehsDebug.Me().Out("UPDATE: quit");
                        resetUpdateFlags();
                        return;
                }
                
                //check long timeout
                if ((System.currentTimeMillis() - updateStarted) > TIMEOUT_WHOLE_FW){
                    ehsDebug.Me().Out("UPDATE: ERROR ...too long time nothing changed, stop update process");
                    resetUpdateFlags();
                    break;
                }
            }
        } catch (Exception e){
            ehsDebug.Me().Out("UPDATE: ERROR:" + e.getMessage());
        }
    }
    
    private byte calcCrcForBuffer(byte arr[], int size){
        byte crc = (byte)0x0;
        
        try{
            for (int i=0; i<size; i++){
                crc = (byte)(((crc & 0xFF) ^ (arr[i] & 0xFF)) & 0xFF);
            }
        }catch (Exception e){
        }
        
        return crc;
    }
    
    private void openUpdateCopy(char rw){  
        
        ehsDebug.Me().Out("FW BIN: open for " + rw);
        
        try{
            fwfc = (FileConnection)Connector.open(FW_FILE_PATH);
            if (rw == 'r'){
                if (fwfc.exists()){
                    fwfcdi = fwfc.openDataInputStream();  
                    fwfcdo = null;
                }
            }
            
            if (rw == 'w'){
                if (fwfc.exists()){
                    fwfc.delete();
                }
                
                fwfc.create();
                fwfcdi = null;
                fwfcdo = fwfc.openDataOutputStream();
            }
            
        }catch (Exception e){
        }
    }
    
    private void savePartToCopy(){
        
        ehsDebug.Me().Out("FW BIN: save part, part size="+recvPacketPayloadSize);
        
        try{
            fwfcdo.write(recvPacket, 22, recvPacketPayloadSize);
            
        }catch (Exception e){
        }
    }
    
    private void closeUpdateCopy(){
        
        ehsDebug.Me().Out("FW BIN: close");
        
        try{
            if (fwfcdi != null)
                fwfcdi.close();
            if (fwfcdo != null)
                fwfcdo.close();
            fwfcdi = null;
            fwfcdo = null;
            fwfc.close();
        }catch (Exception e){
        }
    }
    
    private void deleteUpdateCopy(){
        
        ehsDebug.Me().Out("FW BIN: delete");
        
        try{
            fwfc = (FileConnection)Connector.open(FW_FILE_PATH);
            if (fwfc.exists()){
                fwfc.delete();
            }
            fwfc.close();
            fwfc = null;
            
        }catch (Exception e){
        }
    }
    
    private long recvUpdateReqFrom7188(){
        long recvOffset = -1L;
       
        //to do - fill fwSendOffset
        try{
            int readyFromPort = PORT_I.available();
            if (readyFromPort > 26){
                byte pdata[] = new byte[readyFromPort];
                int readedFromPort = PORT_I.read(pdata, 0, readyFromPort);
                if (readedFromPort > 26){
                    if (pdata[0] == (byte)0x7F){
                        
                        ehsDebug.Me().Out("UPDATE: read from port: SOP found" );
                        
                        boolean imeiCheck = true;
                        for (int i=0; i<15; i++){
                            if ((settings.modemImei.charAt(i) & 0xFF) != (pdata[1+i] & 0xFF)){
                                imeiCheck = false;
                                break;
                            }
                        }
                        
                        ehsDebug.Me().Out("UPDATE: read from port: imei check " + imeiCheck);
                        
                        if (imeiCheck){
                            int pSize = 0;
                            int pOffset = 0 ;
                            if ((pdata[16] == (byte)0x0) && (pdata[17] == (byte)0xC7)){
                                ehsDebug.Me().Out("UPDATE: read from port: CMD FILE GET found");
                                pSize = ((pdata[20] &0xFF) << 8) + (pdata[21] & 0xFF);
                                ehsDebug.Me().Out("UPDATE: read from port: payload size " + pSize);
                                if (pSize > 0){
                                    pOffset = ((pdata[22] &0xFF) << 24) + ((pdata[23] &0xFF) << 16) + ((pdata[24] &0xFF) << 8) + (pdata[25] & 0xFF);
                                    ehsDebug.Me().Out("UPDATE: read from port: file offset " + pOffset);
                                }
                                
                                byte crc = calcCrcForBuffer(pdata, readedFromPort);
                                
                                ehsDebug.Me().Out("UPDATE: read from port: crc wait " + ehsDebug.Me().char2hex((char)(crc & 0xFF)) + " recv " + ehsDebug.Me().char2hex((char)(pdata[26] & 0xFF)));
                                
                                if ((crc == pdata[26]) || (skipCrc)){
                                    recvOffset = (long)pOffset;
                                }
                            }
                        }
                    }
                }
                pdata = null;
            }
        }catch (Exception e){
        }
        
        if (recvOffset != -1L)
            ehsDebug.Me().Out("UPDATE RECV FROM 7188, ASK FW with offset " + recvOffset);
        
        return recvOffset;
    }
    
    private long sendUpdateFilePart(){
        long sendSize = -1L;
        int readSize = -1;
        byte fBuff[] = new byte[1000];
        byte fwreq[] = new byte[1024];
        
        //read file
        try{
            readSize = fwfcdi.read(fBuff, 0, 1000);
        } catch (Exception e){
        }

        //check res
        if (readSize < 0)
            readSize = 0;
        
        //form and send from fwSendOffset
        try{
            fwreq[0] = (byte)0x7f;
            fwreq[1] = (byte)settings.modemImei.charAt(0);
            fwreq[2] = (byte)settings.modemImei.charAt(1);
            fwreq[3] = (byte)settings.modemImei.charAt(2);
            fwreq[4] = (byte)settings.modemImei.charAt(3);
            fwreq[5] = (byte)settings.modemImei.charAt(4);
            fwreq[6] = (byte)settings.modemImei.charAt(5);
            fwreq[7] = (byte)settings.modemImei.charAt(6);
            fwreq[8] = (byte)settings.modemImei.charAt(7);
            fwreq[9] = (byte)settings.modemImei.charAt(8);
            fwreq[10]= (byte)settings.modemImei.charAt(9);
            fwreq[11]= (byte)settings.modemImei.charAt(10);
            fwreq[12]= (byte)settings.modemImei.charAt(11);
            fwreq[13]= (byte)settings.modemImei.charAt(12);
            fwreq[14]= (byte)settings.modemImei.charAt(13);
            fwreq[15]= (byte)settings.modemImei.charAt(14);
            fwreq[16]= (byte)0x0;  //cmd
            fwreq[17]= (byte)0xC7; //cmd
            fwreq[18]= (byte)0x0;  //flags
            fwreq[19]= (byte)0x1;  //flags, have fw
            fwreq[20]= (byte)((readSize >> 8) & 0xFF);  //size
            fwreq[21]= (byte)(readSize & 0xFF);          //size
            if (readSize > 0){
                System.arraycopy(fBuff, 0, fwreq, 22, readSize);
            }
            fwreq[22+readSize]= (byte)calcCrcForBuffer(fwreq, 22+readSize);
            
            //to do: may be sleeps over bytes will be needed
            PORT_O.write(fwreq, 0, 23+readSize);
            fwSendOffset+=readSize;
            sendSize = (long)readSize;
            
        }catch (Exception e){
        }
        
        
        
        return sendSize;
    }
    
    private void formConnectedPacket(boolean updateReady){
        byte fwreq[] = new byte[23];
        
        ehsDebug.Me().Out("FORM CONNECTED PACKET (fw flag="+updateReady+")");
        try {
            fwreq[0] = (byte)0x7f;
            fwreq[1] = (byte)settings.modemImei.charAt(0);
            fwreq[2] = (byte)settings.modemImei.charAt(1);
            fwreq[3] = (byte)settings.modemImei.charAt(2);
            fwreq[4] = (byte)settings.modemImei.charAt(3);
            fwreq[5] = (byte)settings.modemImei.charAt(4);
            fwreq[6] = (byte)settings.modemImei.charAt(5);
            fwreq[7] = (byte)settings.modemImei.charAt(6);
            fwreq[8] = (byte)settings.modemImei.charAt(7);
            fwreq[9] = (byte)settings.modemImei.charAt(8);
            fwreq[10]= (byte)settings.modemImei.charAt(9);
            fwreq[11]= (byte)settings.modemImei.charAt(10);
            fwreq[12]= (byte)settings.modemImei.charAt(11);
            fwreq[13]= (byte)settings.modemImei.charAt(12);
            fwreq[14]= (byte)settings.modemImei.charAt(13);
            fwreq[15]= (byte)settings.modemImei.charAt(14);
            fwreq[16]= (byte)0x0;  //cmd
            fwreq[17]= (byte)0xFC; //cmd
            fwreq[18]= (byte)0x0;  //flags
            if (updateReady){
                fwreq[19]= (byte)0x1;  //flags, have fw
            } else {
                fwreq[19]= (byte)0x0;  //flags, have fw
            }
            fwreq[20]= (byte)0x0;  //size
            fwreq[21]= (byte)0x0;  //size
            fwreq[22]= (byte)calcCrcForBuffer(fwreq, 22);
            
            PORT_O.write(fwreq, 0, 23);
            
        } catch (Exception e){
        
        }
        
        fwreq = null;
    }
    
    private void formImeiAssignPacket(){
        byte fwreq[] = new byte[23];
        
        ehsDebug.Me().Out("FORM IMEI ASSIGN PACKET");
        try {
            fwreq[0] = (byte)0x7f;
            fwreq[1] = (byte)settings.modemImei.charAt(0);
            fwreq[2] = (byte)settings.modemImei.charAt(1);
            fwreq[3] = (byte)settings.modemImei.charAt(2);
            fwreq[4] = (byte)settings.modemImei.charAt(3);
            fwreq[5] = (byte)settings.modemImei.charAt(4);
            fwreq[6] = (byte)settings.modemImei.charAt(5);
            fwreq[7] = (byte)settings.modemImei.charAt(6);
            fwreq[8] = (byte)settings.modemImei.charAt(7);
            fwreq[9] = (byte)settings.modemImei.charAt(8);
            fwreq[10]= (byte)settings.modemImei.charAt(9);
            fwreq[11]= (byte)settings.modemImei.charAt(10);
            fwreq[12]= (byte)settings.modemImei.charAt(11);
            fwreq[13]= (byte)settings.modemImei.charAt(12);
            fwreq[14]= (byte)settings.modemImei.charAt(13);
            fwreq[15]= (byte)settings.modemImei.charAt(14);
            fwreq[16]= (byte)0x0;  //cmd
            fwreq[17]= (byte)0xFE; //cmd
            fwreq[18]= (byte)0x0;  //flags
            fwreq[19]= (byte)0x0;  //flags, have fw
            fwreq[20]= (byte)0x0;  //size
            fwreq[21]= (byte)0x0;  //size
            fwreq[22]= (byte)calcCrcForBuffer(fwreq, 22);
            
            PORT_O.write(fwreq, 0, 23);
            
        } catch (Exception e){
        
        }
        
        fwreq = null;
    }
    
    private boolean handleInputBuffer(int recvFromSockCount){
        boolean res = false;
        try{
            
            if (recvFromSockCount > 0){
                System.arraycopy(inputBuffer, 0, recvPacket, recvPacketSize, recvFromSockCount);
                recvPacketSize+=recvFromSockCount;
                
                ehsDebug.Me().Out("HANDLE INPUT (recvFromSocketCount="+recvFromSockCount+")");
                ehsDebug.Me().OutBuff ("recvPart ", inputBuffer, recvFromSockCount);
            }
            
            // 0 7F
            // 1 IMEI 15 bytes
            // 2 cmd hi
            // 2 cmd lo
            // 3 flags hi
            // 3 flags lo
            // 4 size hi
            // 4 size lo
            // 5 payload
            // 6 crc
            
            ehsDebug.Me().Out("HANDLE INPUT STATE = "+parsePacketState);
            
            
            switch (parsePacketState){
                case 0: //check 7F
                    if (recvPacketSize > 0){
                        if (recvPacket[0] == (byte)0x7F){
                            parsePacketState = 1;
                            res = handleInputBuffer(0);
                        } else {
                            recvPacketSize = 0;
                        }
                    }
                    break;
                    
                case 1: //check imei
                    if (recvPacketSize > 15){
                        boolean imeiCheck = true;
                        
                        for (int i=0; i<15; i++){
                            if ((recvPacket[1+i] & 0xFF) != 0xFF){
                                if ((settings.modemImei.charAt(i) & 0xFF) != (recvPacket[1+i] & 0xFF)){
                                    imeiCheck = false;
                                    break;
                                }
                            }
                        }
                        
                        if (imeiCheck){
                            ehsDebug.Me().Out("HANDLE INPUT check imei ok");
                            parsePacketState = 2;
                            res = handleInputBuffer(0);
                        } else {
                            ehsDebug.Me().Out("HANDLE INPUT check imei err");
                            parsePacketState = 0;
                            recvPacketSize = 0;
                        }
                    } 
                    break;
                    
                //0123456789012345678901
                //B123456789012345CCFFSS
                case 2: //get cmd
                    if (recvPacketSize > 17){
                        ehsDebug.Me().Out("HANDLE INPUT cmd...");
                        parsePacketState = 3;
                        res = handleInputBuffer(0);
                    }
                    break;
                    
                case 3: //get flags
                    if (recvPacketSize > 19){
                        
                        int flags = 0;
                        flags += ((recvPacket[18] & 0xFF) << 8);
                        flags += ((recvPacket[19] & 0xFF));
                        
                        ehsDebug.Me().Out("HANDLE INPUT flags="+flags);
                        
                        if (((flags & 0x1) == 0x1) && (fwRecvState == 0)){
                            fwRecvState = 1;
                            fwRecvOffset = 0;
                            fwSendState = 0;
                            fwSendOffset = 0;
                            recvPacket[19] = (byte)(recvPacket[19] - (byte)0x1);
                            openUpdateCopy('w');
                        }
                       
                        parsePacketState = 4;
                        res = handleInputBuffer(0);
                    }
                    break;
                
                case 4: //get size
                    if (recvPacketSize > 21){
                        int pSize = 0;
                        pSize += ((recvPacket[20] & 0xFF) << 8);
                        pSize += ((recvPacket[21] & 0xFF));
                        
                        ehsDebug.Me().Out("HANDLE INPUT size "+pSize);
                        
                        if (pSize > 0){
                            recvPacketPayloadSize = pSize;
                            parsePacketState = 5;
                        } else {
                            recvPacketPayloadSize = 0;
                            parsePacketState = 6;
                        }
                        res = handleInputBuffer(0);
                    }
                    break;
                    
                case 5: //payload
                    if (recvPacketSize > (22+recvPacketPayloadSize)){
                        ehsDebug.Me().Out("HANDLE INPUT payload, " + recvPacketPayloadSize + " bytes");
                        parsePacketState = 6;
                        res = handleInputBuffer(0);
                    }
                    break;
                    
                case 6: //crc
                    byte crc = calcCrcForBuffer(recvPacket, 22+recvPacketPayloadSize);
                                        
                    ehsDebug.Me().Out("HANDLE INPUT crc (wait=" +ehsDebug.Me().char2hex((char)(crc & 0xFF))+ ", recv=" +ehsDebug.Me().char2hex((char)(recvPacket[22+recvPacketPayloadSize]&0xFF) )+ ")");
                    
                    if ((recvPacket[22+recvPacketPayloadSize] == crc) || (skipCrc)){
                        switch (fwRecvState){
                            case 0:
                                res = true;
                                break;
                                
                            case 1:
                                if (fwFlag == false){
                                    res = true;
                                    fwFlag = true;
                                } else {
                                    res = false;
                                }
                                break;
                                
                            case 2:
                                res = false;
                                
                                if (recvPacketPayloadSize > 0){
                                    savePartToCopy();
                                    fwRecvState = 1;
                                    fwRecvOffset = fwRecvOffset+recvPacketPayloadSize;
                                    recvPacketSize = 0;
                                    recvPacketPayloadSize = 0;
                                } else {
                                    closeUpdateCopy();
                                    fwRecvState = 0;
                                    fwSendState = 1;
                                }
                                break;
                        }
                    } else {
                        //reset buffer due to error crc
                        res = false;
                        resetUpdateFlags();
                        recvPacketSize = 0;
                        recvPacketPayloadSize = 0;
                    }
                    
                    parsePacketState = 0;
                    break;
            }
            
        } catch (Exception e){
            ehsDebug.Me().Out("HANDLE INPUT ERROR " + e.getMessage());
            
            resetUpdateFlags();
            recvPacketSize = 0;
            recvPacketPayloadSize = 0;
            parsePacketState = 0;
            res = false;
        }
        
        return res;
    }

    private void transmitGprs(){
        try{
            int readyFromSocket = SOCK_I.available();
            int readyFromPort = PORT_I.available();
            int readedFromSocket = 0;
            int readedFromPort = 0;
            
            //ask about FW app if needed
            if (fwRecvState > 0){
                switch (fwRecvState){
                    case 1: //ask portion
                        requestUpdatePart(false);
                        fwRecvState = 2;
                        break;

                    case 2: //wait portion
                        //noop
                        break;
                }
            }
                
            if (readyFromSocket > 0){
                if (readyFromSocket > INPUT_BUFF_SIZE)
                    readyFromSocket = INPUT_BUFF_SIZE;

                //update read time
                lastAvailableBytesFromInput = System.currentTimeMillis();
                connectionErrorsCounter = 0;
                
                
                
                //process bytes from input
                readedFromSocket = SOCK_I.read(inputBuffer, 0, readyFromSocket);
                if (handleInputBuffer(readedFromSocket)){
                    //write if possible
                    PORT_O.write(recvPacket, 0, recvPacketSize);
                    recvPacketSize = 0;
                    recvPacketPayloadSize = 0;
                }
            } else {

                //if no input - check timeout
                if ((System.currentTimeMillis() - lastAvailableBytesFromInput) > APP_RX_TX_DELAY){
                    ehsDebug.Me().Out("TIMEOUT on transmit with gprs");
                    settings.online = false;
                }
            }

            if (readyFromPort > 0){
                if (readyFromPort > OUTPUT_BUFF_SIZE)
                    readyFromPort = OUTPUT_BUFF_SIZE;

                readedFromPort = PORT_I.read(outputBuffer, 0, readyFromPort);
                if (fwFlag == false){
                    SOCK_O.write(outputBuffer, 0, readedFromPort);
                    
                    ehsDebug.Me().OutBuff ("send to server ", outputBuffer, readedFromPort);
                }
            }
            
        } catch (Exception e){
            ready = false;
            settings.online = false;
            ehsDebug.Me().Out("ERROR on transmit gprs: " + e.toString());
            settings.setLastError(ERR_CODE_DATA_RX_TX);// unable to transmit data
        }
    }

    private void transmitCsd(){
        try{
            int readyFromCsd = atint.CSD_I.available();
            int readyFromPort = PORT_I.available();
            int readedFromCsd = 0;
            int readedFromPort = 0;

            if (readyFromCsd > 0){
                if (readyFromCsd > INPUT_BUFF_SIZE)
                    readyFromCsd = INPUT_BUFF_SIZE;

                //update read time
                lastAvailableBytesFromInput = System.currentTimeMillis();
                connectionErrorsCounter = 0;
                
                //ask about FW app if needed
                if (fwRecvState > 0){
                    switch (fwRecvState){
                        case 1: //ask portion
                            requestUpdatePart(true);
                            fwRecvState = 2;
                            break;
                            
                        case 2: //wait portion
                            //noop
                            break;
                    }
                }
                
                //process input data
                readedFromCsd = atint.CSD_I.read(inputBuffer, 0, readyFromCsd);
                if (handleInputBuffer(readedFromCsd)){
                    PORT_O.write(recvPacket, 0, recvPacketSize);
                }
            }else {

                //if no input - check timeout
                if ((System.currentTimeMillis() - lastAvailableBytesFromInput) > APP_RX_TX_DELAY){
                    ehsDebug.Me().Out("TIMEOUT on transmit with csd");
                    ready = false;
                }
            }

            if (readyFromPort > 0){
                if (readyFromPort > OUTPUT_BUFF_SIZE)
                    readyFromPort = OUTPUT_BUFF_SIZE;

                readedFromPort = PORT_I.read(outputBuffer, 0, readyFromPort);
                if (fwFlag == false){
                    atint.CSD_O.write(outputBuffer, 0, readedFromPort);
                }
            }

        } catch (Exception e){
            ready = false;
            ehsDebug.Me().Out("ERROR on transmit csd: " + e.toString());
            settings.setLastError(ERR_CODE_DATA_RX_TX);// unable to transmit data
        }
    }

    private void closeSocket(){
        
        ehsDebug.Me().Out("CONNECTION: CLOSE");
        
        try{
            SOCK_I.close();
        } catch (Exception e){}

        try{
            SOCK_O.close();
        } catch (Exception e){}

        try{
            SOCK.close();
        } catch (Exception e){}
        
        SOCK_I = null;
        SOCK_O = null;
        SOCK = null;
        
        settings.online = false;
    }

    private void closePort(){
        try{
            PORT_I.close();
        } catch (Exception e){}

        try{
            PORT_O.close();
        } catch (Exception e){}

        try{
            PORT.close();
        } catch (Exception e){}

        PORT_I = null;
        PORT_O = null;
        PORT = null;

        ready = false;
    }

    private String getSocketParams(){
        String connectionParams;

        connectionParams = "socket://"+settings.serverIp+":"+settings.serverPort+";bearer_type=gprs";

        if(atint.currentSimIndex() == 1){
            connectionParams+=";access_point="+settings.providerApn1;
            if (settings.providerLogin1.length() > 0)
                connectionParams+=";username="+settings.providerLogin1;
            if (settings.providerPassw1.length() > 0)
                connectionParams+=";password="+settings.providerPassw1;
        } else {
            connectionParams+=";access_point="+settings.providerApn2;
            if (settings.providerLogin2.length() > 0)
                connectionParams+=";username="+settings.providerLogin2;
            if (settings.providerPassw2.length() > 0)
                connectionParams+=";password="+settings.providerPassw2;
        }
        
        connectionParams+=";timeout="+settings.providerTimeout;

        ehsDebug.Me().Out("CONNECTOR PARAMS:\r\n" + connectionParams );

        return connectionParams;
    }

    private String getPortParams(){
        return "comm:COM"+settings.portNumber+
                ";baudrate="+settings.portBaud+
                ";parity=none"+
                ";bitsperchar=8"+
                ";stopbits=1"+
                ";blocking=off"+
                ";autocts=off"+
                ";autorts=off";
    }

    private boolean initModem(){
        boolean result = false;

        //creat settings and load its;
        settings = new ehsSettings();
        result = settings.loadSettings();
        if (!result){
            ehsDebug.Me().Out("ERROR on initialize storage");
        }
        
        //create at-listenter and initialize
        atint = new ehsAtInterpreter(settings);
        result = atint.init();
        if (!result){
            ehsDebug.Me().Out("ERROR on initialize atc");
            settings.setLastError(ERR_CODE_AT_PART);// unable to initialize AT part
            return false;
        }
        
        //get imei
        result = atint.getImei();
        if (!result){
            ehsDebug.Me().Out("ERROR on get imie");
            settings.setLastError(ERR_CODE_AT_PART);// unable to initialize AT part
            return false;
        }
        
        //wait register loop done
        while (true){
            
            //check sim pin
            result = atint.isSimPinLockActivated();
            if (result){
                //check pin was specified
                if (settings.simPinPresentForSim(atint.currentSimIndex())){
                    result = atint.enterSimPin();
                    if (!result){
                        ehsDebug.Me().Out("ERROR on enter sim pin, pin is incorrect");
                        settings.setLastError(ERR_CODE_SIM_PIN_BAD);// unable to enter sim pin, pin incorrect
                        return false;
                    }
                } else {
                    ehsDebug.Me().Out("ERROR on enter sim pin, pin not present for sim in settings");
                    settings.setLastError(ERR_CODE_NO_SIM_PIN);// unable to enter sim, pin is emty
                    return false;
                }
            }
            
            //check network registration presence
            for (int t=0; ((t<MAX_APP_REG_TRIES) && (!result)); t++){

                ///slep before check on 6 seconds
                try{
                    Thread.sleep(APP_REG_REACH_DELAY);
                } catch (Exception e){}

                //check registration
                result = atint.isNetRegistrationDone();
            }

            //check registration result
            if (!result){
                ehsDebug.Me().Out("ERROR on get network registration (delay = 1 minute)");
                settings.setLastError(ERR_CODE_REG_ERROR);// unable to register

                //check we can try register with another sim
                if(settings.useSecondSim){
                    result = atint.changeSim();
                    if (!result){
                        ehsDebug.Me().Out("ERROR on network registration with select second sim");
                        settings.setLastError(ERR_CODE_CANT_RUN_SIM2);// unable to change sim
                        return false;
                    }
                } else {
                    ehsDebug.Me().Out("ERROR second sim is not allowed to use, quit");
                    settings.setLastError(ERR_CODE_CANT_USE_SIM2);// unable to change sim, not allowed to use sim 2
                    return false;
                }

            } else {
                
                //exit loop to continue actions
                break;
            }
        }

        //get operator name
        result = atint.getOperatorName();
        if (!result){
            ehsDebug.Me().Out("ERROR can't get operator name");
            settings.setLastError(ERR_CODE_GET_NET_NAME);// unable to get operator name
            return false;
        }

        //init sms part
        result = atint.initSms();
        if(!result){
            ehsDebug.Me().Out("ERROR init sms part");
            settings.setLastError(ERR_CODE_PROCESS_SMS);// unable to attach to gprs service
            return false;
        }

        //make cgattach
        if (settings.useGprs){
            
            result = atint.openGprsPipes();
            if (!result){
                ehsDebug.Me().Out("ERROR can't attach gprs service, quit");
                settings.setLastError(ERR_CODE_CANT_ATTACH);// unable to attach to gprs service
                return false;
            }
        }
        
        //if not specified apns and other settings, set default
        result = settings.setDefaultSettings(atint.currentSimIndex());
        if (result){
            settings.setLastError(ERR_CODE_USE_DEF_APNS);// default settings for apns was applyed
        }
        
        //open com port
        openPort();
        
        //send form imei assign packet
        formImeiAssignPacket();
        
        return true;
    }
    
    private void Sleep(long sleepMs){
        try {
            Thread.sleep(sleepMs);
        } catch (Exception e){}
    }
}
