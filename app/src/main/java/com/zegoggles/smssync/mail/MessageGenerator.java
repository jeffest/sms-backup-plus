package com.zegoggles.smssync.mail;

import android.content.Context;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.Telephony;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;
import com.fsck.k9.mail.Address;
import com.fsck.k9.mail.BodyPart;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.internet.MimeMessage;
import com.fsck.k9.mail.internet.MimeMultipart;
import com.fsck.k9.mail.internet.TextBody;
import com.zegoggles.smssync.Consts;
import com.zegoggles.smssync.contacts.ContactGroupIds;
import com.zegoggles.smssync.preferences.AddressStyle;
import com.zegoggles.smssync.preferences.CallLogTypes;
import com.zegoggles.smssync.preferences.DataTypePreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.fsck.k9.mail.internet.MimeMessageHelper.setBody;
import static com.zegoggles.smssync.App.LOCAL_LOGV;
import static com.zegoggles.smssync.App.TAG;
import static com.zegoggles.smssync.Consts.MMS_PART;

class MessageGenerator {
    private static final String ERROR_PARSING_DATE = "error parsing date";
    private final Context context;
    private final HeaderGenerator headerGenerator;
    private final Address userAddress;
    private final PersonLookup personLookup;
    private final boolean prefix;
    private final @Nullable ContactGroupIds contactGroupIds;
    private final CallFormatter callFormatter;
    private final AddressStyle addressStyle;
    private final MmsSupport mmsSupport;
    private final CallLogTypes callLogTypes;
    private final DataTypePreferences dataTypePreferences;

    MessageGenerator(Context context,
                     Address userAddress,
                     AddressStyle addressStyle,
                     HeaderGenerator headerGenerator,
                     PersonLookup personLookup,
                     boolean mailSubjectPrefix,
                     @Nullable ContactGroupIds contactsToBackup,
                     MmsSupport mmsSupport,
                     CallLogTypes callLogTypes,
                     DataTypePreferences dataTypePreferences) {
        this.headerGenerator = headerGenerator;
        this.userAddress = userAddress;
        this.addressStyle = addressStyle;
        this.context = context;
        this.personLookup = personLookup;
        this.prefix = mailSubjectPrefix;
        this.contactGroupIds = contactsToBackup;
        this.callFormatter = new CallFormatter(this.context.getResources());
        this.mmsSupport = mmsSupport;
        this.dataTypePreferences = dataTypePreferences;
        this.callLogTypes = callLogTypes;
    }

    public  @Nullable Message messageForDataType(Map<String, String> msgMap, DataType dataType) throws MessagingException {
        switch (dataType) {
            case SMS: return messageFromMapSms(msgMap);
            case MMS: return messageFromMapMms(msgMap);
            case CALLLOG: return messageFromMapCallLog(msgMap);
            default: return null;
        }
    }

    private @Nullable Message messageFromMapSms(Map<String, String> msgMap) throws MessagingException {
        final String address = msgMap.get(Telephony.TextBasedSmsColumns.ADDRESS);
        if (TextUtils.isEmpty(address)) return null;

        PersonRecord record = personLookup.lookupPerson(address);
        if (!includePersonInBackup(record, DataType.SMS)) return null;

        final Message msg = new MimeMessage();
        msg.setSubject(getSubject(DataType.SMS, record));
        setBody(msg, new TextBody(msgMap.get(Telephony.TextBasedSmsColumns.BODY)));

        final int messageType = toInt(msgMap.get(Telephony.TextBasedSmsColumns.TYPE));
        if (Telephony.TextBasedSmsColumns.MESSAGE_TYPE_INBOX == messageType) {
            // Received message
            msg.setFrom(record.getAddress(addressStyle));
            msg.setRecipient(Message.RecipientType.TO, userAddress);
        } else {
            // Sent message
            msg.setRecipient(Message.RecipientType.TO, record.getAddress(addressStyle));
            msg.setFrom(userAddress);
        }

        Date sentDate;
        try {
            // TODO: should probably be TextBasedSmsColumns.DATE_SENT
            sentDate = new Date(Long.valueOf(msgMap.get(Telephony.TextBasedSmsColumns.DATE)));
        } catch (NumberFormatException n) {
            Log.e(TAG, ERROR_PARSING_DATE, n);
            sentDate = new Date();
        }

        // see mmsThreadId. Aligning with the MMS thread ID is necessary for a mixed SMS/MMS thread to stay combined in gmail.
        String smsThreadId = msgMap.get(Telephony.BaseMmsColumns.THREAD_ID);

        headerGenerator.setHeaders(msg, msgMap, DataType.SMS, address, smsThreadId, sentDate, messageType);
        return msg;
    }

    private @Nullable Message messageFromMapMms(Map<String, String> msgMap) throws MessagingException {
        if (LOCAL_LOGV) Log.v(TAG, "messageFromMapMms(" + msgMap + ")");

        final Uri mmsUri = Uri.withAppendedPath(Consts.MMS_PROVIDER, msgMap.get(Telephony.BaseMmsColumns._ID));

        MmsSupport.MmsDetails details = mmsSupport.getDetails(mmsUri, addressStyle, msgMap);

        if (details.isEmpty()) {
            Log.w(TAG, "no recipients found");
            return null;
        } else if (!includeInBackup(DataType.MMS, details.getRecipients())) {
            Log.w(TAG, "no recipients included");
            return null;
        }

        final Message msg = new MimeMessage();
        msg.setSubject(getSubject(DataType.MMS, details));


        if (details.inbound) {
            // msg_box == MmsConsts.MESSAGE_BOX_INBOX does not work
            msg.setFrom(details.getSender().getAddress(addressStyle));
            msg.setRecipients(Message.RecipientType.TO, details.getRecipientAddresses(addressStyle)); // Includes everyone that received the MMS in the email "to" field.
        } else {
            msg.setRecipients(Message.RecipientType.TO, details.getRecipientAddresses(addressStyle));
            msg.setFrom(userAddress);
        }

        Date sentDate;
        try {
            sentDate = new Date(1000 * Long.valueOf(msgMap.get(Telephony.BaseMmsColumns.DATE)));
        } catch (NumberFormatException n) {
            Log.e(TAG, ERROR_PARSING_DATE, n);
            sentDate = new Date();
        }
        final int msg_box = toInt(msgMap.get("msg_box"));

        // We could thread by contact ID, not by thread ID. Original author thought this value was more stable.
        // It works pretty badly with MMS threads though.
//        String mmsThreadId = details.getRecipient();

        // We could grab all the raw addresses (phone numbers), and sort/unique/join them for gmail's thread id
        // This doesn't work well, because sometimes phone numbers have a "+1" prefix, sometimes not.
//        Set<String> rawAddresses = new HashSet<>();
//        rawAddresses.addAll(details.rawAddresses);
//        List<String> rawAddressesSorted = new ArrayList<>(rawAddresses.size());
//        rawAddressesSorted.addAll(rawAddresses);
//        Collections.sort(rawAddressesSorted);
//        String mmsThreadId = stringJoin(rawAddressesSorted, "-");

        // We could thread by the messaging app's thread ID. The original author thought this wasn't very stable,
        // but it's the best option for MMS so far, and it looks good from limited testing.
        String mmsThreadId = msgMap.get(Telephony.BaseMmsColumns.THREAD_ID);

        // Tip - if you want to try different strategies for computing mmsThreadId against the same messages,
        // make sure to generate a new MESSAGE_ID as well. It seems like gmail caches REFERENCES if you reuse the same MESSAGE_ID.

        headerGenerator.setHeaders(msg, msgMap, DataType.MMS, details.getFirstRawAddress(), mmsThreadId, sentDate, msg_box);
        MimeMultipart body = MimeMultipart.newInstance();

        for (BodyPart p : mmsSupport.getMMSBodyParts(Uri.withAppendedPath(mmsUri, MMS_PART))) {
            body.addBodyPart(p);
        }

        setBody(msg, body);
        return msg;
    }

    private  @Nullable Message messageFromMapCallLog(Map<String, String> msgMap) throws MessagingException {
        final String address = msgMap.get(CallLog.Calls.NUMBER);
        final int callType = toInt(msgMap.get(CallLog.Calls.TYPE));

        if (!callLogTypes.isTypeEnabled(callType)) {
            if (LOCAL_LOGV) Log.v(TAG, "ignoring call log entry: " + msgMap);
            return null;
        }
        PersonRecord record = personLookup.lookupPerson(address);
        if (!includePersonInBackup(record, DataType.CALLLOG)) return null;

        final Message msg = new MimeMessage();
        msg.setSubject(getSubject(DataType.CALLLOG, record));

        switch (callType) {
            case CallLog.Calls.OUTGOING_TYPE:
                msg.setFrom(userAddress);
                msg.setRecipient(Message.RecipientType.TO, record.getAddress(addressStyle));
                break;
            case CallLog.Calls.MISSED_TYPE:
            case CallLog.Calls.INCOMING_TYPE:
            case CallLog.Calls.REJECTED_TYPE:
            case CallLog.Calls.VOICEMAIL_TYPE:

                msg.setFrom(record.getAddress(addressStyle));
                msg.setRecipient(Message.RecipientType.TO, userAddress);
                break;
            default:
                // some weird phones seem to have SMS in their call logs, which is
                // not part of the official API.
                Log.w(TAG, "ignoring unknown call type: " + callType);
                return null;
        }

        final int duration = msgMap.get(CallLog.Calls.DURATION) == null ? 0 :
                toInt(msgMap.get(CallLog.Calls.DURATION));

        setBody(msg, new TextBody(callFormatter.format(callType, record.getNumber(), duration)));
        Date sentDate;
        try {
            sentDate = new Date(Long.valueOf(msgMap.get(CallLog.Calls.DATE)));
        } catch (NumberFormatException n) {
            Log.e(TAG, ERROR_PARSING_DATE, n);
            sentDate = new Date();
        }
        headerGenerator.setHeaders(msg, msgMap, DataType.CALLLOG, address, record.getId(), sentDate, callType);
        return msg;
    }

    private String getSubject(@NonNull DataType type, @NonNull MmsSupport.MmsDetails details) {
        // If you're in a group text with several people, ensure the email subject will look like
        // "SMS with Alice/Bob/Charles/YourPhoneNumber"

        Set<String> allNames = new HashSet<>(); // eliminate duplicates. There will be some - android's MMS apis are weird.

        // Try to eliminate the current user's number from this, so MMS subjects align with SMS subjects, so that they get a single thread in gmail.
        // Only supported for MMS messages with 1 other person.
        // For group texts (more than 2 people involved), it's not as important to remove current user,
        // b/c the thread is entirely MMS, so it doesn't need to align with SMS.
        // Of course it would be nice/cleaner to remove the current user's phone number from group MMS subjects too.
        // But when we parse sender/recipients in MmsSupport.getDetails(), there's no apparent way to determine
        // which recipient is the current user. So this avoids adding another mechanism to grab the current user's phone number(s).
        if (details.getRecipients().size() == 1 && details.inbound) {
            // We don't need to add the one recipient (the current user)
        } else {
            for (PersonRecord recipient : details.getRecipients()) {
                allNames.add(recipient.getName());
            }
        }

        if (details.getRecipients().size() == 1 && !details.inbound) {
            // for outgoing MMS messages, we don't want to include the sender (aligns with SMS)
        } else {
            allNames.add(details.sender.getName());
        }


        List<String> allNamesSorted = new ArrayList<>(allNames.size());
        allNamesSorted.addAll(allNames);
        Collections.sort(allNamesSorted); // keep subjects stable so gmail can keep threads consistent

        String namesJoinedBySlash = stringJoin(allNamesSorted, "/");

        return prefix ?
                String.format(Locale.ENGLISH, "[%s] %s", dataTypePreferences.getFolder(type), namesJoinedBySlash) :
                context.getString(type.withField, namesJoinedBySlash);
    }

    private String stringJoin(List<String> allNamesSorted, String delimeter) {
        StringBuilder builder = new StringBuilder();

        boolean isFirst = true;
        for (String name : allNamesSorted) {
            if (!isFirst) {
                builder.append(delimeter);
            }
            isFirst = false;

            builder.append(name);
        }
        return builder.toString();
    }

    private String getSubject(@NonNull DataType type, @NonNull PersonRecord record) {
        return prefix ?
                String.format(Locale.ENGLISH, "[%s] %s", dataTypePreferences.getFolder(type), record.getName()) :
                context.getString(type.withField, record.getName());
    }

    private boolean includeInBackup(DataType type, Iterable<PersonRecord> records) {
        for (PersonRecord r : records) {
            if (includePersonInBackup(r, type)) {
                return true;
            }
        }
        return false;
    }

    private boolean includePersonInBackup(PersonRecord record, DataType type) {
        final boolean backup = contactGroupIds == null || contactGroupIds.contains(record);
        //noinspection PointlessBooleanExpression,ConstantConditions
        if (LOCAL_LOGV && !backup) Log.v(TAG, "not backing up " + type + " / " + record);
        return backup;
    }

    private static int toInt(String s) {
        try {
             return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
