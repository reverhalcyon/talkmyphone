package com.googlecode.talkmyphone;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.filter.MessageTypeFilter;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Packet;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.IBinder;
import android.provider.Contacts;
import android.provider.Contacts.People;
import android.provider.Settings;
import android.telephony.gsm.SmsManager;
import android.text.ClipboardManager;
import android.widget.Toast;

public class XmppService extends Service {

    // Service instance
    private static XmppService instance = null;

    // XMPP connection
    private String mLogin;
    private String mPassword;
    private String mTo;
    private ConnectionConfiguration mConnectionConfiguration = null;
    private XMPPConnection mConnection = null;
    private PacketListener mPacketListener;
    private boolean notifyApplicationConnection;

    // ring
    private MediaPlayer mMediaPlayer = null;

    // last person who sent sms/who we sent an sms to
    private String lastRecipient = null;

    // intents for sms sending
    PendingIntent sentPI = null;
    PendingIntent deliveredPI = null;
    BroadcastReceiver sentSmsReceiver = null;
    BroadcastReceiver deliveredSmsReceiver = null;
    private boolean notifySmsSent;
    private boolean notifySmsDelivered;

    // battery
    private BroadcastReceiver mBatInfoReceiver = null;
    private boolean notifyBattery;

    // Contact searching
    private final static String cellPhonePattern = "0[67]\\d{8}";
    private final static String internationalPrefix = "+33";

    /** import the preferences */
    private void importPreferences() {
        SharedPreferences prefs = getSharedPreferences("TalkMyPhone", 0);
        String serverHost = prefs.getString("serverHost", "");
        int serverPort = prefs.getInt("serverPort", 0);
        String serviceName = prefs.getString("serviceName", "");
        mConnectionConfiguration = new ConnectionConfiguration(serverHost, serverPort, serviceName);
        mTo = prefs.getString("notifiedAddress", "");
        mPassword =  prefs.getString("password", "");
        boolean useDifferentAccount = prefs.getBoolean("useDifferentAccount", false);
        if (useDifferentAccount) {
            mLogin = prefs.getString("login", "");
        } else{
            mLogin = mTo;
        }
        notifyApplicationConnection = prefs.getBoolean("notifyApplicationConnection", true);
        notifyBattery = prefs.getBoolean("notifyBattery", true);
        notifySmsSent = prefs.getBoolean("notifySmsSent", true);
        notifySmsDelivered = prefs.getBoolean("notifySmsDelivered", true);
    }

    /** clear the sms monitoring related stuff */
    private void clearSmsMonitors() {
        if (sentSmsReceiver != null) {
            unregisterReceiver(sentSmsReceiver);
        }
        if (deliveredSmsReceiver != null) {
            unregisterReceiver(deliveredSmsReceiver);
        }
        sentPI = null;
        deliveredPI = null;
        sentSmsReceiver = null;
        deliveredSmsReceiver = null;
    }

    /** reinit sms monitors (that tell the user the status of the sms) */
    private void initSmsMonitors() {
        if (notifySmsSent) {
            String SENT = "SMS_SENT";
            sentPI = PendingIntent.getBroadcast(this, 0,
                new Intent(SENT), 0);
            sentSmsReceiver = new BroadcastReceiver(){
                @Override
                public void onReceive(Context arg0, Intent arg1) {
                    switch (getResultCode())
                    {
                        case Activity.RESULT_OK:
                            send("SMS sent");
                            break;
                        case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                            send("Generic failure");
                            break;
                        case SmsManager.RESULT_ERROR_NO_SERVICE:
                            send("No service");
                            break;
                        case SmsManager.RESULT_ERROR_NULL_PDU:
                            send("Null PDU");
                            break;
                        case SmsManager.RESULT_ERROR_RADIO_OFF:
                            send("Radio off");
                            break;
                    }
                }
            };
            registerReceiver(sentSmsReceiver, new IntentFilter(SENT));
        }
        if (notifySmsDelivered) {
            String DELIVERED = "SMS_DELIVERED";
            deliveredPI = PendingIntent.getBroadcast(this, 0,
                    new Intent(DELIVERED), 0);
            deliveredSmsReceiver = new BroadcastReceiver(){
                @Override
                public void onReceive(Context arg0, Intent arg1) {
                    switch (getResultCode())
                    {
                        case Activity.RESULT_OK:
                            send("SMS delivered");
                            break;
                        case Activity.RESULT_CANCELED:
                            send("SMS not delivered");
                            break;
                    }
                }
            };
            registerReceiver(deliveredSmsReceiver, new IntentFilter(DELIVERED));
        }
    }

    /** clears the XMPP connection */
    private void clearConnection() {
        if (mConnection != null) {
            if (mPacketListener != null) {
                mConnection.removePacketListener(mPacketListener);
            }
            mConnection.disconnect();
        }
        mConnection = null;
        mPacketListener = null;
        mConnectionConfiguration = null;
    }

    /** init the XMPP connection */
    private void initConnection() {
        mConnection = new XMPPConnection(mConnectionConfiguration);
        try {
            mConnection.connect();
            mConnection.login(mLogin, mPassword);
        } catch (XMPPException e) {
            e.printStackTrace();
        }
        /*
        Timer t = new Timer();
        t.schedule(new TimerTask() {
                @Override
                public void run() {
                    Presence presence = new Presence(Presence.Type.available);
                    mConnection.sendPacket(presence);
                }
            }, 0, 60*1000);
        */
        PacketFilter filter = new MessageTypeFilter(Message.Type.chat);
        mPacketListener = new PacketListener() {
            public void processPacket(Packet packet) {
                Message message = (Message) packet;
                if (message.getFrom().startsWith(mTo + "/")
                && !message.getFrom().equals(mConnection.getUser()) // filters self-messages
                ) {
                    if (message.getBody() != null) {
                        onCommandReceived(message.getBody());
                    }
                }
            }
        };
        mConnection.addPacketListener(mPacketListener, filter);
        // Send welcome message
        if (notifyApplicationConnection) {
            send("Welcome to TalkMyPhone. Send \"?\" for getting help");
        }
    }

    /** Reconnects using the current preferences (assumes the service is started)*/
    public void reConnect() {
        mConnection.disconnect();
        try {
            mConnection.connect();
            mConnection.login(mLogin, mPassword);
        } catch (XMPPException e) {
            e.printStackTrace();
        }
    }

    /** clear the battery monitor*/
    private void clearBatteryMonitor() {
        if (mBatInfoReceiver != null) {
            unregisterReceiver(mBatInfoReceiver);
        }
        mBatInfoReceiver = null;
    }

    /** init the battery stuff */
    private void initBatteryMonitor() {
        if (notifyBattery) {
            mBatInfoReceiver = new BroadcastReceiver(){
                private int lastPercentageNotified = -1;
                @Override
                public void onReceive(Context arg0, Intent intent) {
                    int level = intent.getIntExtra("level", 0);
                    if (lastPercentageNotified == -1) {
                        notifyAndSavePercentage(level);
                    } else {
                        if (level != lastPercentageNotified && level % 5 == 0) {
                            notifyAndSavePercentage(level);
                        }
                    }
                }
                private void notifyAndSavePercentage(int level) {
                    send("Battery level " + level + "%");
                    lastPercentageNotified = level;
                }
            };
            registerReceiver(mBatInfoReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        }
    }

    /** clears the media player */
    private void clearMediaPlayer() {
        if (mMediaPlayer != null) {
            mMediaPlayer.stop();
        }
        mMediaPlayer = null;
    }

    /** init the media player */
    private void initMediaPlayer() {
        Uri alert = Settings.System.DEFAULT_RINGTONE_URI ;
        mMediaPlayer = new MediaPlayer();
        try {
            mMediaPlayer.setDataSource(this, alert);
            mMediaPlayer.setAudioStreamType(AudioManager.STREAM_ALARM);
            mMediaPlayer.setLooping(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void _onStart() {
        // Get configuration
        if (instance == null)
        {
            instance = this;

            // first, clean everything
            clearConnection();
            clearSmsMonitors();
            clearMediaPlayer();
            clearBatteryMonitor();

            // then, re-import preferences
            importPreferences();

            // finally, init everything
            initSmsMonitors();
            initBatteryMonitor();
            initMediaPlayer();
            initConnection();

            if (mConnection.isConnected() && mConnection.isAuthenticated()) {
                Toast.makeText(this, "TalkMyPhone started", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "TalkMyPhone failed to authenticate", Toast.LENGTH_SHORT).show();
                onDestroy();
            }
        }
    }

    public static XmppService getInstance() {
        return instance;
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        _onStart();
    }

    @Override
    public void onStart(Intent intent, int startId) {
        _onStart();
    };

    @Override
    public void onDestroy() {
        stopLocatingPhone();

        clearSmsMonitors();
        clearMediaPlayer();
        clearBatteryMonitor();
        clearConnection();

        instance = null;

        Toast.makeText(this, "TalkMyPhone stopped", Toast.LENGTH_SHORT).show();
    }

    /** sends a message to the user */
    public void send(String message){
        Message msg = new Message(mTo, Message.Type.chat);
        msg.setBody(message);
        mConnection.sendPacket(msg);
    }

    /**
     * Tries to get the contact display name of the specified phone number.
     * If not found, returns the argument.
     */
    public String getContactName (String phoneNumber) {
        String res = phoneNumber;
        ContentResolver resolver = getContentResolver();
        String[] projection = new String[] {
                Contacts.Phones.DISPLAY_NAME,
                Contacts.Phones.NUMBER };
        Uri contactUri = Uri.withAppendedPath(Contacts.Phones.CONTENT_FILTER_URL, Uri.encode(phoneNumber));
        Cursor c = resolver.query(contactUri, projection, null, null, null);
        if (c.moveToFirst()) {
            String name = c.getString(c.getColumnIndex(Contacts.Phones.DISPLAY_NAME));
            res = name;
        }
        return res;
    }

    /**
     * Returns a dictionary of <ID, name> where the names match the argument
     */
    private Dictionary<Long, String> getMatchingContacts(String searchedName) {
        Dictionary<Long, String> res = new Hashtable<Long, String>();
        if (!searchedName.equals(""))
        {
            ContentResolver resolver = getContentResolver();
            String[] projection = new String[] {
                    Contacts.People._ID,
                    Contacts.People.NAME
                    };
            Uri contactUri = Uri.withAppendedPath(People.CONTENT_FILTER_URI, Uri.encode(searchedName));
            Cursor c = resolver.query(contactUri, projection, null, null, null);
            for (boolean hasData = c.moveToFirst() ; hasData ; hasData = c.moveToNext()) {
                Long id = getLong(c, People._ID);
                if (null != id) {
                    String contactName = getString(c, People.NAME);
                    if(null != contactName) {
                        res.put(id, contactName);
                    }
                }
            }
            c.close();
        }
        return res;
    }

    /**
     * Returns a dictionary < phoneNumber, contactName >
     * with all matching mobile phone for the argument
     */
    private Dictionary<String, String> getMobilePhones(String contact) {
        Dictionary<String, String> res = new Hashtable<String, String>();
        if (isCellPhoneNumber(contact)) {
            res.put(contact, getContactName(contact));
        } else {
            // get the matching contacts, dictionary of < id, names >
            Dictionary<Long, String> contacts = getMatchingContacts(contact);
            if (contacts.size() > 0) {
                ContentResolver resolver = getContentResolver();
                Enumeration<Long> e = contacts.keys();
                while( e. hasMoreElements() ){
                    Long id = e.nextElement();

                    Uri personUri = ContentUris.withAppendedId(People.CONTENT_URI, id);
                    Uri phonesUri = Uri.withAppendedPath(personUri, People.Phones.CONTENT_DIRECTORY);
                    String[] proj = new String[] {Contacts.Phones.NUMBER, Contacts.Phones.LABEL, Contacts.Phones.TYPE};
                    Cursor c = resolver.query(phonesUri, proj, null, null, null);

                    for (boolean hasData = c.moveToFirst() ; hasData ; hasData = c.moveToNext()) {
                        String number = getString(c,Contacts.Phones.NUMBER);
                        if (isCellPhoneNumber(number)) {
                            res.put(number, contacts.get(id));
                        }
                    }
                    // manage not french cell phones
                    if (res.size() == 0) {
                        for (boolean hasData = c.moveToFirst() ; hasData ; hasData = c.moveToNext()) {
                            String number = getString(c,Contacts.Phones.NUMBER);
                            if (getLong(c,Contacts.Phones.TYPE) == Contacts.Phones.TYPE_MOBILE) {
                                res.put(number, contacts.get(id));
                            }
                        }
                    }
                    c.close();
                }
            }
        }
        return res;
    }

    private Long getLong(Cursor c, String col) {
        return c.getLong(c.getColumnIndex(col));
    }

    private String getString(Cursor c, String col) {
        return c.getString(c.getColumnIndex(col));
    }

    private boolean isCellPhoneNumber(String number) {
        String cleanNumber = number
                                .replace("(", "")
                                .replace(")", "")
                                .replace(" ", "")
                                .replace(internationalPrefix, "0");
        return cleanNumber.matches(cellPhonePattern);
    }

    public void setLastRecipient(String phoneNumber) {
        lastRecipient = phoneNumber;
    }

    /** handles the different commands */
    private void onCommandReceived(String commandLine) {
        try {
            String command;
            String args;
            if (-1 != commandLine.indexOf(":")) {
                command = commandLine.substring(0, commandLine.indexOf(":"));
                args = commandLine.substring(commandLine.indexOf(":") + 1);
            } else {
                command = commandLine;
                args = "";
            }

            if (command.equals("?")) {
                StringBuilder builder = new StringBuilder();
                builder.append("Available commands:\n");
                builder.append("- \"?\": shows this help.\n");
                builder.append("- \"reply:message\": send a sms to your last recipient with content message.\n");
                builder.append("- \"sms:contact:message\": sends a sms to number with content message.\n");
                builder.append("- \"where\": sends you google map updates about the location of the phone until you send \"stop\"\n");
                builder.append("- \"ring\": rings the phone until you send \"stop\"\n");
                builder.append("- \"copy:text\": copy text to clipboard\n");
                builder.append("- paste links, open it with the appropriate app\n");
                send(builder.toString());
            }
            else if (command.equals("sms")) {
                int separatorPos = args.indexOf(":");
                String contact = null;
                String message = null;
                if (-1 != separatorPos) {
                    contact = args.substring(0, separatorPos);
                    setLastRecipient(contact);
                    message = args.substring(separatorPos + 1);
                    sendSMS(message, contact);
                } else {
                    contact = args;
                    readSMS(contact, 5);
                }
            }
            else if (command.equals("reply")) {
                if (lastRecipient == null) {
                    send("Error: no recipient registered.");
                } else {
                    sendSMS(args, lastRecipient);
                }
            }
            else if (command.equals("copy")) {
                copyToClipboard(args);
            }
            else if (command.equals("where")) {
                send("Start locating phone");
                startLocatingPhone();
            }
            else if (command.equals("stop")) {
                send("Stopping ongoing actions");
                stopLocatingPhone();
                stopRinging();
            }
            else if (command.equals("ring")) {
                send("Ringing phone");
                ring();
            }
            else if (command.equals("http")) {
                open("http:" + args);
            }
            else if (command.equals("https")) {
                open("https:" + args);
            }
            else {
                send('"'+ commandLine + '"' + ": unknown command. Send \"?\" for getting help");
            }
        } catch (Exception ex) {
            send("Error : " + ex);
        }
    }

    /** Sends a sms to the specified phone number */
    public void sendSMSByPhoneNumber(String message, String phoneNumber) {
        SmsManager sms = SmsManager.getDefault();
        ArrayList<String> messages = sms.divideMessage(message);
        send("Sending sms to " + getContactName(phoneNumber));
        for (int i=0; i < messages.size(); i++) {
            if (i >= 1) {
                send("sending part " + i + "/" + messages.size() + " of splitted message");
            }
            sms.sendTextMessage(phoneNumber, null, messages.get(i), sentPI, deliveredPI);
            addSmsToSentBox(message, phoneNumber);
        }
    }

    /** sends a SMS to the specified contact */
    public void sendSMS(String message, String contact) {
        if (isCellPhoneNumber(contact)) {
            sendSMSByPhoneNumber(message, contact);
        } else {
            Dictionary<String, String> mobilePhones = getMobilePhones(contact);
            if (mobilePhones.size() > 1) {
                send("Specify more details:");
                Enumeration<String> e = mobilePhones.keys();
                while( e.hasMoreElements() ){
                    String number = e.nextElement();
                    String name = mobilePhones.get(number);
                    send(name + " - " + number);
                }
            } else if (mobilePhones.size() == 1) {
                String phoneNumber = mobilePhones.keys().nextElement();
                sendSMSByPhoneNumber(message, phoneNumber);
            } else {
                send("No match for \"" + contact + "\"");
            }
        }
    }

    /** reads (count) SMS from all contacts matching pattern */
    public void readSMS(String contact, Integer count) {

        Dictionary<Long, String> contacts = getMatchingContacts(contact);

        if (contacts.size() > 0) {
            ContentResolver resolver = getContentResolver();
            Enumeration<Long> e = contacts.keys();
            while( e.hasMoreElements() ) {
                Long id = e.nextElement();
                if(null != id) {
                    Uri mSmsQueryUri = Uri.parse("content://sms/inbox");
                    String columns[] = new String[] { "person", "body", "date", "status"};
                    Cursor c = resolver.query(mSmsQueryUri, columns, "person = " + id, null, null);

                    if (c.getCount() > 0) {
                        send(contacts.get(id));
                        Integer i = 0;
                        for (boolean hasData = c.moveToFirst() ; hasData && i++ < count ; hasData = c.moveToNext()) {
                            Date date = new Date();
                            date.setTime(Long.parseLong(getString(c ,"date")));
                            send( date.toLocaleString() + " - " + getString(c ,"body"));
                        }
                        if (i < count) {
                            send("Only got " + i + " sms");
                        }
                    } else {
                        send("No sms found");
                    }
                    c.close();
                }
            }
        } else {
            send("No match for \"" + contact + "\"");
        }
    }

    /** Starts the geolocation service */
    private void startLocatingPhone() {
        Intent intent = new Intent(this, LocationService.class);
        startService(intent);
    }

    /** Stops the geolocation service */
    private void stopLocatingPhone() {
        Intent intent = new Intent(this, LocationService.class);
        stopService(intent);
    }

    /** copy text to clipboard */
    private void copyToClipboard(String text) {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setText(text);
            send("Text copied");
        }
        catch(Exception ex) {
            send("Clipboard access failed");
        }
    }

    /** lets the user choose an activity compatible with the url */
    private void open(String url) {
        Intent target = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        Intent intent = Intent.createChooser(target, "TalkMyPhone: choose an activity");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    /** makes the phone ring */
    private void ring() {
        final AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) != 0) {
            try {
                mMediaPlayer.prepare();
            } catch (IllegalStateException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mMediaPlayer.start();
        }
    }

    /** Stops the phone from ringing */
    private void stopRinging() {
        mMediaPlayer.stop();
    }

    /** Adds the text of the message to the sent box */
    private void addSmsToSentBox(String message, String phoneNumber) {
        ContentValues values = new ContentValues();
        values.put("address", phoneNumber);
        values.put("date", System.currentTimeMillis());
        values.put("body", message);
        getContentResolver().insert(Uri.parse("content://sms/sent"), values);
    }
}
