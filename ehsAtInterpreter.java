

import com.siemens.icm.io.ATCommand;
import com.siemens.icm.io.ATCommandListener;
import java.io.InputStream;
import java.io.OutputStream;

/**
 *
 * @author Osipov, OOO SKB Mayak, Russia, Nigny Novgorod, 2013
 */

public class ehsAtInterpreter implements ehsDefines, ehsErrorCodes, ATCommandListener{

    private ATCommand ehs;
    public InputStream CSD_I = null;
    public OutputStream CSD_O = null;
    private int currentSim = 1;
    private ehsSettings settings;
   
    public boolean csdIsEstablished = false;
    private int maxSmscount = 10;
    private String balanceAskerNumber = "";
    private String smsCommands[] = {"USPD IP",       //0  - set-get ip and port for connection
                                    "USPD APN1",     //1  - set-get apn string for sim 1
                                    "USPD APN2",     //2  - set-get apn string for sim 2
                                    "USPD LP1",      //3  - set-get login password string for sim 1
                                    "USPD LP2",      //4  - set-get login password string for sim 2
                                    "USPD PIN1",     //5  - set-get sim pin for sim 1
                                    "USPD PIN2",     //6  - set-get sim pin for sim 2
                                    "USPD SIM2",     //7  - set-get allow flag to use sim 2 as reserved sim
                                    "USPD SN",       //8  - get only - return IMEI
                                    "USPD REBOOT",   //9  - set only - ask to reboot moded
                                    "USPD SQ",       //10 - get only - return signal quality
                                    "USPD ERR",      //11 - get only - return last error number
                                    "USPD REG",      //12 - get only - return reg operator name and sim index (1 or 2)
                                    "USPD BALANCE",  //13 - get only - return balance string from operator
                                    "USPD MODE",     //14 - set-get mode of connection gprs of csd
                                    "USPD GETVER",   //15 - get only - version
                                    "USPD CONN",     //16 - get onlt - mode + ip+port
                                    "USPD GETSNSQ",  //17 - get only - imei + signal
                                    "USPD TOUCH"     //18 - set only - mode to grps, ip+port
                                            };      
    
    public ehsAtInterpreter(ehsSettings _settings){
        settings = _settings;
    }

    //ovverider for listener call back
    public void ATEvent(String Event){
        try{
            //event associated with ussd request
            if (Event.toUpperCase().indexOf("+CUSD: ")!= -1){
                ehsDebug.Me().Out("USSD answer recieved:\r\n" + Event + "answer will setn to: " + balanceAskerNumber);
                int uanswA = Event.indexOf(",\"");
                int uanswB = Event.indexOf("\",");
                ehsDebug.Me().Out("USSD answer run " + uanswA);
                ehsDebug.Me().Out("USSD answer end " + uanswB);
                if ((uanswA != -1) && (uanswB != -1)){
                    sendSms(balanceAskerNumber, smsCommands[13] + " " + Event.substring(uanswA+2, uanswB));
                    return;
                }
            }

            //event associated with new message indication
            if (Event.toUpperCase().indexOf("+CMTI: ")!= -1){
                ehsDebug.Me().Out("SMS message recieved:\r\n" + Event);
                //+CMTI: "SM",7
                int indexA = Event.indexOf("\",");
                int indexB = Event.indexOf("\r\n", indexA+1);
                if ((indexA!=-1) && (indexB!=-1)){
                    String smsIndexStr = Event.substring(indexA+2, indexB);
                    int smsIndex = Integer.parseInt(smsIndexStr);
                    processSms(smsIndex);
                }
                return;
            }

            //check ring during incoming call
            if (Event.toUpperCase().indexOf("RING") != -1){
                ehsDebug.Me().Out("RING recieved");
                if (settings.useGprs == false) {
                    //if csd check weather this call is csd or not
                    int doti=0;
                    String answ = at("AT+CLCC\r");
                    if (answ.toUpperCase().indexOf("+CLCC")!=-1){
                        StringBuffer sb = new StringBuffer(answ);
                        doti = sb.toString().indexOf(",");
                        if (doti!=-1) {
                            sb.delete(0, doti+1);
                            doti = sb.toString().indexOf(",");
                            if (doti!=-1) {
                                sb.delete(0, doti+1);
                                doti = sb.toString().indexOf(",");
                                if (doti!=-1) {
                                    sb.delete(0, doti+1);
                                    doti = sb.toString().indexOf(",");
                                    sb.delete(doti, sb.length());
                                    int serviceOfCall;
                                    String serviceOfCallStr = sb.toString();
                                    ehsDebug.Me().Out("call is have service: " + serviceOfCallStr);
                                    serviceOfCall = Integer.parseInt(serviceOfCallStr);
                                    if ((serviceOfCall == 1) || (serviceOfCall == 2)) {
                                        ehsDebug.Me().Out("call is have service: csd or fax, so answer ");
                                        answerToCsdCall();
                                        return;
                                    }
                                }
                            }
                        }
                    }

                } else {
                    
                    //send busy
                    ehsDebug.Me().Out("call is have service: voice or other, so terminate call");
                    at("AT^SHUP=17\r");
                }

                return;
            }
            
        } catch (Exception e){
            ehsDebug.Me().Out("ERROR atc event callback");
            settings.setLastError(ERR_CODE_EVENT_AT);// errors processing at comands
        }
    }

    //ovverider for listener call back
    public void	CONNChanged(boolean SignalState){
        ehsDebug.Me().Out("AT EVENT / CONNChanged to : " + SignalState);
        if (!settings.useGprs){
            if (SignalState == false)
                hangupCall();
        }
    }

    //ovverider for listener call back
    public void	DCDChanged(boolean SignalState){
        ehsDebug.Me().Out("AT EVENT / DCDChanged to : " + SignalState);
    }

    //ovverider for listener call back
    public void	DSRChanged(boolean SignalState){
        ehsDebug.Me().Out("AT EVENT / DSRChanged to : " + SignalState);
    }

    //ovverider for listener call back
    public void	RINGChanged(boolean SignalState) {
        //ehsDebug.Me().Out("AT EVENT / RINGChanged to : " + SignalState);
    }

    public boolean init(){
        boolean result = false;
        
        //create ATC
        try{
            ehs = new ATCommand(false);
            ehs.addListener(this);
            
        } catch (Exception e){
            ehsDebug.Me().Out("ERROR atc create");
            settings.setLastError(ERR_CODE_AT_PART);
            return false;
        }

        //init ATC
        try{
            String answ = at("ATE0\r");
            if (answ.toUpperCase().indexOf("OK")!= -1)
                return true;

        } catch (Exception e){
            ehsDebug.Me().Out("ERROR atc processing ate0");
            settings.setLastError(ERR_CODE_PROCESS_AT);// errors processing at comands
            return false;
        }

        //init gpios
        //try{
        //    String answ = at("AT^SPIO=1\r");
        //    if (answ.toUpperCase().indexOf("OK")!= -1){
        //        answ = at("AT^SCPIN=1,7,1,0\r");
        //        if (answ.toUpperCase().indexOf("OK")!= -1){
        //            return true;
        //        }
        //    }
        //
        //} catch (Exception e){
        //    ehsDebug.Me().Out("ERROR atc processing pio open");
        //    settings.setLastError(ERR_CODE_PROCESS_AT);// errors processing at comands
        //    return false;
        //}

        return false;
    }

    public synchronized String at(String cmd){
        try{
            return ehs.send(cmd);

        } catch (Exception e){
            String dbgCmd;
            dbgCmd = cmd.replace('\r', ' ');
            dbgCmd = cmd.replace('\n', ' ');
            ehsDebug.Me().Out("ERROR atc procesing at cmd [" + dbgCmd + "]");
            settings.setLastError(ERR_CODE_PROCESS_AT);// errors processing at comands
        }

        return "ERROR IN ATC";
    }

    private void answerToCsdCall(){
        try{
            String answer = at("ATA\r");
            ehsDebug.Me().Out ("ATA answer is: "+answer);

            if (answer.toUpperCase().indexOf("CONNECT 9600")!=-1){
                CSD_I = ehs.getDataInputStream();
                CSD_O = ehs.getDataOutputStream();
                csdIsEstablished = true;
            } else {
                hangupCall();
            }

        } catch (Exception e){
             ehsDebug.Me().Out ("ERROR in answerToCsdCall: " + e.toString());
             settings.setLastError(ERR_CODE_CSD_ANSWER);// errors processing at comands
        }
    }
    
    public synchronized void hangupCall(){
        if (!csdIsEstablished)
            return;

        String answ = "";

        try{
            try{
                answ = ehs.breakConnection();
                ehsDebug.Me().Out ("+++ answer is: "+answ);
            } catch (Exception e){}

            //ath
            answ = at("ATH\r");
            ehsDebug.Me().Out ("ATH answer is: "+answ);
                
            csdIsEstablished = false;

            try{
                CSD_I.close();
            } finally {
                CSD_I = null;
            }

            try{
                CSD_O.close();
            } finally {
                CSD_O = null;
            }

        } catch (Exception e){
             ehsDebug.Me().Out ("ERROR in csd hangupCall: " + e.toString());
        }
    }

    public boolean isNetRegistrationDone(){
        String answ = at("AT+CREG?\r");
        ehsDebug.Me().Out ("REG ANSW: " + answ.replace('\r', ' ').replace('\n', ' '));
        if ((answ.toUpperCase().indexOf("CREG: 0,1")!= -1) || (answ.toUpperCase().indexOf("CREG: 0,5")!= -1))
            return true;

        return false;
    }

    public boolean getImei(){
        String answ = at("AT+CGSN\r");
        int okPresentPosition = answ.toUpperCase().indexOf("OK");
        if (okPresentPosition != -1){
            settings.modemImei = answ.substring(2, okPresentPosition-4);
            ehsDebug.Me().Out("MODEM IMEI  [" + settings.modemImei + "]");
            return true;
        }

        return false;
    }

    public boolean getOperatorName(){
        String answ = at("AT+COPS?\r");
        if (answ.toUpperCase().indexOf("+COPS: ")!= -1){
            int indexA = answ.indexOf("\"");
            int indexB = answ.indexOf("\"", indexA+1);
            if ((indexA!=-1) && (indexB!=-1)){
                settings.operatorName = answ.substring(indexA+1, indexB);
                ehsDebug.Me().Out("REGITRED TO [" + settings.operatorName + "]");
                return true;
            }
        }

        return false;
    }

    public boolean changeSim(){
        String answ = "";
        try{
            //setup pio to use anohter sim
            switch (currentSim){
                case 1:
                    answ = at("AT^SSIO=7,1");
                    if (answ.toUpperCase().indexOf("OK")!= -1)
                        currentSim = 2;
                    else
                        return false;
                    break;

                case 2:
                    answ = at("AT^SSIO=7,0");
                    if (answ.toUpperCase().indexOf("OK")!= -1)
                        currentSim = 1;
                    else
                        return false;
                    break;
            }

            //reset op name
            settings.operatorName = "ERROR";

            //restart network registration
            answ = at("AT+COPS=0\r");
            if (answ.toUpperCase().indexOf("OK")!= -1)
                return true;

            
        } catch (Exception e){
            ehsDebug.Me().Out("ERROR gpio set procesing " + e.toString());
            settings.setLastError(ERR_CODE_GPIO_PART);// errors processing gpio set
        }
        
        return false;
    }

    public boolean isSimPinLockActivated(){
        String answ = at("AT+CPIN?\r");
        if (answ.toUpperCase().indexOf("+CPIN: SIM PIN")!= -1){
            return true;
        } else if(answ.toUpperCase().indexOf("+CPIN: READY")!= -1){
            return false;
        }

        return false;
    }

    public boolean enterSimPin(){
        String cmd = "AT+CPIN=";
        switch (currentSim){
            case 1: cmd+=settings.sim1Pin; break;
            case 2: cmd+=settings.sim2Pin; break;
        }

        //enter sim pin
        at(cmd + "\r");

        return isSimPinLockActivated();
    }

    public boolean openGprsPipes(){
        String answ = at("AT+CGATT=1\r");
        if (answ.toUpperCase().indexOf("OK")!= -1) {
            ehsDebug.Me().Out("GPRS ATTACH [ OK ]");
            return true;
        }

        return false;
    }

    public int currentSimIndex(){
        return currentSim;
    }

    public void perfomeReboot(){
         at("AT+CFUN=1,1\r");
         while (true){
             //no operaion
             //simple wait reboot of modem
         }
    }

    public String getSq(){
        try{
            int s, b;
            String sq = at("AT+CSQ"+"\r");

            //process S
            int runS = sq.indexOf("+CSQ: ");
            int endS = sq.indexOf(",");
            if ((runS == -1) || (endS == -1))
                return "ERROR";
            else
                s = Integer.parseInt(sq.substring(runS+6, endS));

            //process B
            int runB = endS+1;
            int endB = sq.indexOf("\r", runB);
            if ((runB == -1) || (endB == -1))
                return "ERROR";
            else
                b = Integer.parseInt(sq.substring(runB, endB));

            //convert
            s=-113+s*2;
            if(b==99) b=0; else b=b+1;

            //return
            return s+" dbm / BER: "+b;

        } catch (Exception e) {
            ehsDebug.Me().Out("ERROR in get sq: " + e.toString());
            settings.setLastError(ERR_CODE_PROCESS_AT);// errors parse sq
        }

        return "ERROR";
    }

    public String getBalance(){

        try{
            switch(settings.operatorIndex){
                case GSM_NETWORK_MTS:
                    at("AT+CUSD=1,\"#100#\",15\r");
                    break;

                case GSM_NETWORK_BEELINE:
                    at("AT+CUSD=1,\"*102#\",15\r");
                    break;

                case GSM_NETWORK_TELE2:
                    at("AT+CUSD=1,\"*105#\",15\r");
                    break;

                case GSM_NETWORK_MEGAFON:
                    at("AT+CUSD=1,\"*102#\",15\r");
                    break;

                default:
                    return "ussd is not supported";

            }


        } catch (Exception e){
            ehsDebug.Me().Out("ERROR in ussd process: " + e.toString());
            settings.setLastError(ERR_CODE_PROCESS_USSD);// errors while ussd
            return "ussd error";
        }

        return "";
    }

    public boolean initSms(){
        try{
            String answ = "";

            answ = at("AT&D0\r"); //set dtr behaviour
            if (answ.toUpperCase().indexOf("OK")==-1) return false;

            answ = at("AT+CSMS=0\r"); //setup gsm foramt
            if (answ.toUpperCase().indexOf("OK")==-1) return false;

            answ = at("AT+CSCS=\"GSM\"\r"); //setup GSM encoding
            if (answ.toUpperCase().indexOf("OK")==-1) return false;

            answ = at("AT+CMGF=1\r"); //setup text format for sms messages
            if (answ.toUpperCase().indexOf("OK")==-1) return false;

            answ = at("AT+CPMS=\"SM\",\"SM\",\"SM\"\r"); //setup preferred ME storage
            if (answ.toUpperCase().indexOf("OK")==-1) return false;

            answ = at("AT+CPMS?\r"); //get settings of storage
            if (answ.toUpperCase().indexOf("OK")==-1) return false;

            //parse maximum size of storage
            //+CPMS: "SM",8,20,"SM",8,20,"SM",8,20
            //8,20,"SM",8,20,"SM",8,20
            //20
            StringBuffer sb = new StringBuffer(answ);
            int index = sb.toString().indexOf("SM");
            if (index == -1) return false; else sb.delete(0,index + 4);
            index = sb.toString().indexOf(",");
            if (index == -1) return false; else sb.delete(0,index + 1);

            //setup maximum size of storage
            index = sb.toString().indexOf(",");
            ehsDebug.Me().Out("SMS STORAGE max size str: " + sb.toString().substring(0,index));
            maxSmscount = Integer.parseInt(sb.toString().substring(0,index));
            ehsDebug.Me().Out("SMS STORAGE max size: " + maxSmscount);

            //process previously collected sms messages
            processCollectedMessages();

            //setup new message indicator
            answ = at("AT+CNMI=1,1\r"); //get settings of storage
            if (answ.toUpperCase().indexOf("OK")==-1) return false;

            return true;

        } catch (Exception e){
            ehsDebug.Me().Out("ERROR in sms listener init: " + e.toString());
            settings.setLastError(ERR_CODE_PROCESS_SMS);// errors processing sms loop
        }

        return false;
    }

    private void processCollectedMessages(){
        for (int index = 0; index < maxSmscount; index++){
            processSms(index);
        }
    }

    private void processSms(int smsIndex){

        ehsDebug.Me().Out("Parse sms index " + smsIndex);
        
        String smsData = at("AT+CMGR="+smsIndex+"\r");
        if (smsData.toUpperCase().indexOf("OK") != -1){
            if (smsData.toUpperCase().indexOf("+CMGR: 0,,0") == -1){
                at("AT+CMGD="+smsIndex+"\r");
                parse(smsData);
            }
        }
    }

    private void parse(String smsData){
        try{
            int index = 0;
            int command = -1;
            String phone = "";
            String smsBody = "";
            String applyParam = "";
            boolean smsToSet = false;
            
            //parse sms data such as
            //+CMGR: "REC READ","+79081659282",,"13/07/12,15:34:10+16"
            //Reboot
            
            String smsDataDebug = smsData.replace('\r', 'R');
            smsDataDebug = smsDataDebug.replace('\n', 'N');
            ehsDebug.Me().Out("SMS PARSE / SMS DATA ["+ smsDataDebug +"]");


            //parse phone number
            int indexphA = smsData.indexOf("\",\"");
            int indexphB = smsData.indexOf("\",", indexphA+1);

            ehsDebug.Me().Out("SMS PARSE / phone A ["+ indexphA +"]");
            ehsDebug.Me().Out("SMS PARSE / phone B ["+ indexphB +"]");

            if ((indexphA!=-1) && (indexphB!=-1)){
                phone = smsData.substring(indexphA+3, indexphB);
                ehsDebug.Me().Out("SMS PARSE / Phone ["+ phone +"]");
            }

            //parse sms message
            int indexrnA = smsData.indexOf("\r", 2);
            int indexrnB = smsData.indexOf("\r\n", indexrnA+1);

            ehsDebug.Me().Out("SMS PARSE / command A ["+ indexphA +"]");
            ehsDebug.Me().Out("SMS PARSE / command B ["+ indexphB +"]");


            if ((indexrnA!=-1) && (indexrnB!=-1)){
                smsBody = smsData.substring(indexrnA+2, indexrnB);
                ehsDebug.Me().Out("SMS PARSE / command ["+ smsBody +"]");
            }

            while (index < smsCommands.length){
                if(smsBody.toUpperCase().startsWith(smsCommands[index])){
                    command = index;
                    break;
                }
                index++;
            }

            ehsDebug.Me().Out("SMS PARSE / command index ["+ command +"]");

            if (command == -1)
                return;

            // parse apply parameters
            int preambuleLen = smsCommands[command].length();
            int paramsStart = preambuleLen+1;
            if (smsBody.length() > (preambuleLen+1)){
                applyParam = smsBody.substring(paramsStart);
            }
            
            ehsDebug.Me().Out("SMS PARSE / command data ["+ applyParam +"]");

            //check applay parameters is present
            if (applyParam.length() > 0)
                smsToSet = true;
            
            ehsDebug.Me().Out("SMS PARSE / smsToSet ["+ smsToSet +"]");

            if (smsToSet){
                if (command == 18) { //touch
                    String prevIpAndMode = "";
                    if (settings.useGprs){
                        prevIpAndMode = " GPRS " + settings.serverIp + ":"+settings.serverPort;
                    } else {
                        prevIpAndMode = " CSD";
                    }
                    settings.setUseGprsConnection(true);
                    applyParameter(0, applyParam);
                    sendSms(phone, "TOUCH OK, prev mode " + prevIpAndMode);
                } else {
                    applyParameter(command, applyParam);
                    answerToSet(command, phone);
                }
                
            } else {
                answerToGet(command, phone);
            }

        } catch (Exception e){
            ehsDebug.Me().Out("ERROR in sms parser part: " + e.toString());
            settings.setLastError(ERR_CODE_PARSE_SMS);// errors parse sms
        }
    }

    private void applyParameter(int command, String applyParam){
        int dotIndex = -1;
        try{
            switch(command){
                case 0: //ip
                    dotIndex = applyParam.indexOf(":");
                    String sIp ="";
                    String sPs = "";
                    int sPi = 0;
                    if (dotIndex != -1){
                        sIp = applyParam.substring(0, dotIndex);
                        sPs = applyParam.substring(dotIndex+1, applyParam.length());
                        if (sPs.length() > 0){
                            try{
                                sPi = Integer.parseInt(sPs);
                            } catch (Exception e){
                                sPi = 0;
                            }
                        }
                    }
                    settings.setServerAndPort(sIp, sPi);
                    break;
                case 1: //apn 1
                    settings.setApn1(applyParam);
                    break;
                case 2: //apn 2
                    settings.setApn2(applyParam);
                    break;
                case 3: //lp1
                    String L1 = "";
                    String P1 = "";
                    dotIndex = applyParam.indexOf(" / ");
                    if (dotIndex != -1){
                        L1 = applyParam.substring(0, dotIndex);
                        P1 = applyParam.substring(dotIndex+3, applyParam.length());
                        
                        int atIndex = -1;
                        
                        atIndex = L1.indexOf("=at;=");
                        if (atIndex > -1){
                            String L0="";
                            L0+=L1.substring(0, atIndex);
                            L0+="@";
                            L0+=L1.substring(atIndex+5, L1.length());
                            L1=L0;
                        }
                        
                        atIndex = P1.indexOf("=at;=");
                        if (atIndex > -1){
                            String P0="";
                            P0+=P1.substring(0, atIndex);
                            P0+="@";
                            P0+=P1.substring(atIndex+5, L1.length());
                            P1=P0;
                        }
                    }
                    settings.setLP1(L1, P1);
                    break;
                case 4: //lp2
                    String L2 = "";
                    String P2 = "";
                    dotIndex = applyParam.indexOf(" / ");
                    if (dotIndex != -1){
                        L2 = applyParam.substring(0, dotIndex);
                        P2 = applyParam.substring(dotIndex+3, applyParam.length());
                    }
                    settings.setLP2(L2, P2);
                    break;
                case 5: //pin1
                    settings.setSim1Pin(applyParam);
                    break;
                case 6: //pin2
                    settings.setSim2Pin(applyParam);
                    break;
                case 7: //use sim2 or not
                    if (applyParam.toUpperCase().indexOf("Y")!=-1){
                        settings.setSim2UseFlag(true);
                    } else {
                        settings.setSim2UseFlag(false);
                    }
                    break;
                case 14: //mode
                    if (applyParam.toUpperCase().indexOf("GPRS")!=-1){
                        settings.setUseGprsConnection(true);
                    } else {
                        settings.setUseGprsConnection(false);
                    }
                    break;
                case 18: //touch ip:port 
                    //on level higher aplly as command 0 
                    break;
            }
        } catch (Exception e){
            ehsDebug.Me().Out("ERROR in sms apply param: " + e.toString());
            settings.setLastError(ERR_CODE_APPLY_SET_SMS);// errors parse sms
        }
    }

    private void answerToSet(int command, String phone){
        try{
            sendSms(phone, smsCommands[command] + " OK");

        }catch (Exception e){
            ehsDebug.Me().Out("ERROR in sms get param: " + e.toString());
            settings.setLastError(ERR_CODE_ANSWER_SMS);// errors parse sms
        }
    }

    private void answerToGet(int command, String phone){
        try{
            switch(command){
                case 0: //ip
                    sendSms(phone, "IP " + settings.serverIp+":"+settings.serverPort);
                    break;
                case 1: //apn 1
                    sendSms(phone, "APN1 " + settings.providerApn1);
                    break;
                case 2: //apn 2
                    sendSms(phone, "APN2 " + settings.providerApn2);
                    break;
                case 3: //lp1
                    sendSms(phone, "LP1 " + settings.providerLogin1+" / "+settings.providerPassw1);
                    break;
                case 4: //lp2
                    sendSms(phone, "LP2 " + settings.providerLogin2+" / "+settings.providerPassw2);
                    break;
                case 5: //pin1
                    sendSms(phone, "PIN1 " + settings.sim1Pin);
                    break;
                case 6: //pin2
                    sendSms(phone, "PIN2 " + settings.sim2Pin);
                    break;
                case 7: //use sim2 or not
                    if (settings.useSecondSim){
                        sendSms(phone, "USE 2 SIM ON");
                    } else {
                        sendSms(phone, "USE 2 SIM OFF");
                    }
                    break;
                case 8: //sn
                    sendSms(phone, "SN " + settings.modemImei);
                    break;
                case 9: //reboot
                    sendSms(phone, "will reboot in next 30 seconds");
                    perfomeReboot();
                    break;
                case 10: //sq
                    sendSms(phone, "SQ " + getSq());
                    break;
                case 11: //error (get)
                    sendSms(phone, "LAST ERR " + settings.lastErrorCode);
                    break;
                case 12: //get reg name
                    sendSms(phone, "REG TO " + settings.operatorName);
                    break;
                case 13: //get balance
                    balanceAskerNumber = phone;
                    String balanceError = getBalance();
                    if (balanceError.length() > 0){
                        sendSms(phone, "BALANCE " + balanceError);
                    }
                    break;
                case 14: //get use gprs or csd
                    if (settings.useGprs){
                        sendSms(phone, "MODE GPRS");
                    } else {
                        sendSms(phone, "MODE CSD");
                    }
                    break;
                case 15: //get ver
                    sendSms(phone, "VER " + VERSION);
                    break;
                case 16: //get conn
                    if (settings.useGprs){
                        String answer = "CONN GPRS";
                        if (settings.online){
                            answer += " ON to";
                        } else {
                            answer += " OFF to";
                        }
                        answer += " " + settings.serverIp +":" + settings.serverPort;
                        sendSms(phone, answer);
                    } else {
                        String answer = "CONN CSD";
                        if (csdIsEstablished){
                             answer += " ON call";
                        } else {
                            answer += " WAIT call";
                        }
                        sendSms(phone, answer);
                    }
                    break;
                case 17: //get snsq
                    sendSms(phone, "SN " + settings.modemImei + " SQ " + getSq());
                    break;
            }
        }catch (Exception e){
            ehsDebug.Me().Out("ERROR in sms get param: " + e.toString());
            settings.setLastError(ERR_CODE_ANSWER_SMS);// errors parse sms
        }
    }

    private void sendSms(String number, String message) {
        try {

            ehsDebug.Me().Out("SMS PARSE / SEND SMS ["+ number + " / "+ message +"]");

            String prompt = at("AT+CMGS="+number+"\r");
            if(prompt.indexOf(">") != -1) {
                at(message + ((char)26));
            }

        } catch (Exception e) {
            ehsDebug.Me().Out("ERROR in sms send: " + e.toString());
            settings.setLastError(ERR_CODE_ANSWER_SMS);// errors parse sms
        }
    }
}
