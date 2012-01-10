/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (c) 2011-2012, Code Aurora Forum. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.phone;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.AsyncQueryHandler;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.SystemProperties;
import android.provider.CallLog.Calls;
import android.provider.ContactsContract.PhoneLookup;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;
import android.telephony.TelephonyManager;
import android.telephony.ServiceState;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.CallerInfoAsyncQuery;

import com.android.internal.telephony.Phone;

/**
 * NotificationManager-related utility code for the Phone app.
 *
 * This is a singleton object which acts as the interface to the
 * framework's NotificationManager, and is used to display status bar
 * icons and control other status bar-related behavior.
 *
 * @see PhoneApp.notificationMgr
 */
public class MSimNotificationMgr extends NotificationMgr {
    private static final String LOG_TAG = "MSimNotificationMgr";
    private static final boolean DBG =
            (PhoneApp.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);

    private static final String[] CALL_LOG_PROJECTION = new String[] {
        Calls._ID,
        Calls.NUMBER,
        Calls.DATE,
        Calls.DURATION,
        Calls.TYPE,
    };

    static final int VOICEMAIL_NOTIFICATION_SUB2 = 20;
    static final int CALL_FORWARD_NOTIFICATION_SUB2 = 21;
    /**
     * Private constructor (this is a singleton).
     * @see init()
     */
    private MSimNotificationMgr(PhoneApp app) {
        super(app);
    }

    /**
     * Initialize the singleton NotificationMgr instance.
     *
     * This is only done once, at startup, from PhoneApp.onCreate().
     * From then on, the NotificationMgr instance is available via the
     * PhoneApp's public "notificationMgr" field, which is why there's no
     * getInstance() method here.
     */
    /* package */ static MSimNotificationMgr init(PhoneApp app) {
        synchronized (MSimNotificationMgr.class) {
            if (sInstance == null) {
                sInstance = new MSimNotificationMgr(app);
                // Update the notifications that need to be touched at startup.
                sInstance.updateNotificationsAtStartup();
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return (MSimNotificationMgr) sInstance;
        }
    }

    /*
     * Makes sure phone-related notifications are up to date on a
     * freshly-booted device.
     */
    protected void updateNotificationsAtStartup() {
        if (DBG) log("updateNotificationsAtStartup()...");

        // instantiate query handler
        mQueryHandler = new MSimQueryHandler(mContext.getContentResolver());

        // setup query spec, look for all Missed calls that are new.
        StringBuilder where = new StringBuilder("type=");
        where.append(Calls.MISSED_TYPE);
        where.append(" AND new=1");

        // start the query
        if (DBG) log("- start call log query...");
        mQueryHandler.startQuery(CALL_LOG_TOKEN, null, Calls.CONTENT_URI,  CALL_LOG_PROJECTION,
                where.toString(), null, Calls.DEFAULT_SORT_ORDER);

        // Update (or cancel) the in-call notification
        if (DBG) log("- updating in-call notification at startup...");
        updateInCallNotification();

        // Depend on android.app.StatusBarManager to be set to
        // disable(DISABLE_NONE) upon startup.  This will be the
        // case even if the phone app crashes.
    }

   /**
     * Class used to run asynchronous queries to re-populate
     * the notifications we care about.
     */
    private class MSimQueryHandler extends NotificationMgr.QueryHandler {

        /**
         * Used to store relevant fields for the Missed Call
         * notifications.
         */
        private class NotificationInfo {
            public String name;
            public String number;
            public String label;
            public long date;
            public int subscription;
        }

        public MSimQueryHandler(ContentResolver cr) {
            super(cr);
        }

        /**
         * Handles the query results.  There are really 2 steps to this,
         * similar to what happens in CallLogActivity.
         *  1. Find the list of missed calls
         *  2. For each call, run a query to retrieve the caller's name.
         */
        @Override
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
            // TODO: it would be faster to use a join here, but for the purposes
            // of this small record set, it should be ok.

            // Note that CursorJoiner is not useable here because the number
            // comparisons are not strictly equals; the comparisons happen in
            // the SQL function PHONE_NUMBERS_EQUAL, which is not available for
            // the CursorJoiner.

            // Executing our own query is also feasible (with a join), but that
            // will require some work (possibly destabilizing) in Contacts
            // Provider.

            // At this point, we will execute subqueries on each row just as
            // CallLogActivity.java does.
            switch (token) {
                case CALL_LOG_TOKEN:
                    if (DBG) log("call log query complete.");

                    // initial call to retrieve the call list.
                    if (cursor != null) {
                        while (cursor.moveToNext()) {
                            // for each call in the call log list, create
                            // the notification object and query contacts
                            MSimQueryHandler.NotificationInfo n = getNotificationInfo (cursor);

                            if (DBG) log("query contacts for number: " + n.number);

                            mQueryHandler.startQuery(CONTACT_TOKEN, n,
                                    Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, n.number),
                                    PHONES_PROJECTION, null, null, PhoneLookup.NUMBER);
                        }

                        if (DBG) log("closing call log cursor.");
                        cursor.close();
                    }
                    break;
                case CONTACT_TOKEN:
                    if (DBG) log("contact query complete.");

                    // subqueries to get the caller name.
                    if ((cursor != null) && (cookie != null)){
                        NotificationInfo n = (NotificationInfo) cookie;

                        if (cursor.moveToFirst()) {
                            // we have contacts data, get the name.
                            if (DBG) log("contact :" + n.name + " found for phone: " + n.number);
                            n.name = cursor.getString(
                                    cursor.getColumnIndexOrThrow(PhoneLookup.DISPLAY_NAME));
                        }

                        // send the notification
                        if (DBG) log("sending notification.");
                        notifyMissedCall(n.name, n.number, n.label, n.date, n.subscription);

                        if (DBG) log("closing contact cursor.");
                        cursor.close();
                    }
                    break;
                default:
            }
        }

        /**
         * Factory method to generate a NotificationInfo object given a
         * cursor from the call log table.
         */
        MSimQueryHandler.NotificationInfo getNotificationInfo(Cursor cursor) {
            NotificationInfo n = new NotificationInfo();
            n.name = null;
            n.number = cursor.getString(cursor.getColumnIndexOrThrow(Calls.NUMBER));
            n.label = cursor.getString(cursor.getColumnIndexOrThrow(Calls.TYPE));
            n.date = cursor.getLong(cursor.getColumnIndexOrThrow(Calls.DATE));

            // make sure we update the number depending upon saved values in
            // CallLog.addCall().  If either special values for unknown or
            // private number are detected, we need to hand off the message
            // to the missed call notification.
            if ( (n.number.equals(CallerInfo.UNKNOWN_NUMBER)) ||
                 (n.number.equals(CallerInfo.PRIVATE_NUMBER)) ||
                 (n.number.equals(CallerInfo.PAYPHONE_NUMBER)) ) {
                n.number = null;
            }

            // TODO MultiSim functionality: Plan to add the right subscription value to the
            // call log db and the same can be used to display for various
            // notifications such as missed call. For now, using default
            // subscription.
            n.subscription = PhoneApp.getInstance().getDefaultSubscription();
            if (DBG) log("NotificationInfo constructed for number: " + n.number);
            return n;

        }
    }

    /**
     * Displays a notification about a missed call.
     *
     * @param nameOrNumber either the contact name, or the phone number if no contact
     * @param label the label of the number if nameOrNumber is a name, null if it is a number
     * @param subscription the subscription on which the call was received
     */
    //TODO MultiSim functionality: Display subscription info.
    void notifyMissedCall(String name, String number, String label, long date, int subscription) {
        // When the user clicks this notification, we go to the call log.
        final Intent callLogIntent = PhoneApp.getInstance().createCallLogIntent(subscription);

        // Never display the missed call notification on non-voice-capable
        // devices, even if the device does somehow manage to get an
        // incoming call.
        if (!PhoneApp.sVoiceCapable) {
            if (DBG) log("notifyMissedCall: non-voice-capable device, not posting notification");
            return;
        }

        // title resource id
        int titleResId;
        // the text in the notification's line 1 and 2.
        String expandedText, callName;

        // increment number of missed calls.
        mNumberMissedCalls++;

        // get the name for the ticker text
        // i.e. "Missed call from <caller name or number>"
        if (name != null && TextUtils.isGraphic(name)) {
            callName = name;
        } else if (!TextUtils.isEmpty(number)){
            callName = number;
        } else {
            // use "unknown" if the caller is unidentifiable.
            callName = mContext.getString(R.string.unknown);
        }

        // display the first line of the notification:
        // 1 missed call: call name
        // more than 1 missed call: <number of calls> + "missed calls"
        if (mNumberMissedCalls == 1) {
            titleResId = R.string.notification_missedCallTitle;
            expandedText = callName;
        } else {
            titleResId = R.string.notification_missedCallsTitle;
            expandedText = mContext.getString(R.string.notification_missedCallsMsg,
                    mNumberMissedCalls);
        }

        // make the notification
        Notification note = new Notification(
                android.R.drawable.stat_notify_missed_call, // icon
                mContext.getString(R.string.notification_missedCallTicker, callName), // tickerText
                date // when
                );
        note.setLatestEventInfo(mContext, mContext.getText(titleResId), expandedText,
                PendingIntent.getActivity(mContext, 0, callLogIntent, 0));
        note.flags |= Notification.FLAG_AUTO_CANCEL;
        // This intent will be called when the notification is dismissed.
        // It will take care of clearing the list of missed calls.
        note.deleteIntent = createClearMissedCallsIntent();

        configureLedNotification(note);
        mNotificationManager.notify(MISSED_CALL_NOTIFICATION, note);
    }

    /**
     * Updates the message waiting indicator (voicemail) notification.
     *
     * @param visible true if there are messages waiting
     */
    /* package */
    void updateMwi(boolean visible, Phone phone) {
        if (DBG) log("updateMwi(): " + visible);
        int subscription = phone.getSubscription();
        if (visible) {
            int resId = android.R.drawable.stat_notify_voicemail;
            int[] iconId = {android.R.drawable.stat_notify_voicemail,
                   android.R.drawable.stat_notify_voicemail};

            // This Notification can get a lot fancier once we have more
            // information about the current voicemail messages.
            // (For example, the current voicemail system can't tell
            // us the caller-id or timestamp of a message, or tell us the
            // message count.)

            // But for now, the UI is ultra-simple: if the MWI indication
            // is supposed to be visible, just show a single generic
            // notification.

            String notificationTitle = mContext.getString(R.string.notification_voicemail_title);
            String vmNumber = phone.getVoiceMailNumber();
            if (DBG) log("- got vm number: '" + vmNumber + "'"
                             + " on Subscription: " + subscription);
                resId = iconId[subscription];

            // Watch out: vmNumber may be null, for two possible reasons:
            //
            //   (1) This phone really has no voicemail number
            //
            //   (2) This phone *does* have a voicemail number, but
            //       the SIM isn't ready yet.
            //
            // Case (2) *does* happen in practice if you have voicemail
            // messages when the device first boots: we get an MWI
            // notification as soon as we register on the network, but the
            // SIM hasn't finished loading yet.
            //
            // So handle case (2) by retrying the lookup after a short
            // delay.

            if ((vmNumber == null) && !phone.getIccRecordsLoaded()) {
                if (DBG) log("- Null vm number: SIM records not loaded (yet)...");

                // TODO: rather than retrying after an arbitrary delay, it
                // would be cleaner to instead just wait for a
                // SIM_RECORDS_LOADED notification.
                // (Unfortunately right now there's no convenient way to
                // get that notification in phone app code.  We'd first
                // want to add a call like registerForSimRecordsLoaded()
                // to Phone.java and GSMPhone.java, and *then* we could
                // listen for that in the CallNotifier class.)

                // Limit the number of retries (in case the SIM is broken
                // or missing and can *never* load successfully.)
                if (mVmNumberRetriesRemaining-- > 0) {
                    if (DBG) log("  - Retrying in " + VM_NUMBER_RETRY_DELAY_MILLIS + " msec...");
                    ((MSimCallNotifier)PhoneApp.getInstance().notifier).sendMwiChangedDelayed(
                            VM_NUMBER_RETRY_DELAY_MILLIS, phone);
                    return;
                } else {
                    Log.w(LOG_TAG, "NotificationMgr.updateMwi: getVoiceMailNumber() failed after "
                          + MAX_VM_NUMBER_RETRIES + " retries; giving up.");
                    // ...and continue with vmNumber==null, just as if the
                    // phone had no VM number set up in the first place.
                }
            }

            if (TelephonyCapabilities.supportsVoiceMessageCount(phone)) {
                int vmCount = phone.getVoiceMessageCount();
                String titleFormat = mContext.getString(
                        R.string.notification_voicemail_title_count);
                notificationTitle = String.format(titleFormat, vmCount);
            }

            String notificationText;
            if (TextUtils.isEmpty(vmNumber)) {
                notificationText = mContext.getString(
                        R.string.notification_voicemail_no_vm_number);
            } else {
                notificationText = String.format(
                        mContext.getString(R.string.notification_voicemail_text_format),
                        PhoneNumberUtils.formatNumber(vmNumber));
            }

            Intent intent = new Intent(Intent.ACTION_CALL,
                    Uri.fromParts("voicemail", "", null));
            intent.putExtra("subId", subscription);
            PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0, intent, 0);

            Notification notification = new Notification(
                    resId,  // icon
                    null, // tickerText
                    System.currentTimeMillis()  // Show the time the MWI notification came in,
                                                // since we don't know the actual time of the
                                                // most recent voicemail message
                    );
            notification.setLatestEventInfo(
                    mContext,  // context
                    notificationTitle,  // contentTitle
                    notificationText,  // contentText
                    pendingIntent  // contentIntent
                    );
            notification.defaults |= Notification.DEFAULT_SOUND;
            notification.flags |= Notification.FLAG_NO_CLEAR;
            configureLedNotification(notification);
            mNotificationManager.notify(VOICEMAIL_NOTIFICATION, notification);
        } else {
            mNotificationManager.cancel(VOICEMAIL_NOTIFICATION);
        }
    }

    /**
     * Updates the message call forwarding indicator notification.
     *
     * @param visible true if there are messages waiting
     */
    /* package */ void updateCfi(boolean visible, int subscription) {
        if (DBG) log("updateCfi(): " + visible + "sub = " + subscription);
        int [] callfwdIcon = {R.drawable.stat_sys_phone_call_forward_sub1,
                R.drawable.stat_sys_phone_call_forward_sub2};

        int notificationId = (subscription == 0) ? CALL_FORWARD_NOTIFICATION :
                CALL_FORWARD_NOTIFICATION_SUB2;

        if (visible) {
            // If Unconditional Call Forwarding (forward all calls) for VOICE
            // is enabled, just show a notification.  We'll default to expanded
            // view for now, so the there is less confusion about the icon.  If
            // it is deemed too weird to have CF indications as expanded views,
            // then we'll flip the flag back.

            // TODO: We may want to take a look to see if the notification can
            // display the target to forward calls to.  This will require some
            // effort though, since there are multiple layers of messages that
            // will need to propagate that information.

            int resId = callfwdIcon[subscription];

            Notification notification;
            final boolean showExpandedNotification = true;
            if (showExpandedNotification) {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.setClassName("com.android.phone",
                        "com.android.phone.CallFeaturesSetting");

                notification = new Notification(
                        resId,  // icon
                        null, // tickerText
                        0); // The "timestamp" of this notification is meaningless;
                            // we only care about whether CFI is currently on or not.
                notification.setLatestEventInfo(
                        mContext, // context
                        mContext.getString(R.string.labelCF), // expandedTitle
                        mContext.getString(R.string.sum_cfu_enabled_indicator), // expandedText
                        PendingIntent.getActivity(mContext, 0, intent, 0)); // contentIntent
            } else {
                notification = new Notification(
                        resId,  // icon
                        null,  // tickerText
                        System.currentTimeMillis()  // when
                        );
            }

            notification.flags |= Notification.FLAG_ONGOING_EVENT;  // also implies FLAG_NO_CLEAR

            mNotificationManager.notify(
                    notificationId,
                    notification);
        } else {
            mNotificationManager.cancel(notificationId);
        }
    }

    /**
     * Update notification about no service of user selected operator
     *
     * @param serviceState Phone service state
     */
    void updateNetworkSelection(int serviceState, Phone phone) {
        if (TelephonyCapabilities.supportsNetworkSelection(phone)) {
            // get the shared preference of network_selection.
            // empty is auto mode, otherwise it is the operator alpha name
            // in case there is no operator name, check the operator numeric
            SharedPreferences sp =
                    PreferenceManager.getDefaultSharedPreferences(mContext);
            String networkSelection =
                    sp.getString(PhoneBase.NETWORK_SELECTION_NAME_KEY, "");
            if (TextUtils.isEmpty(networkSelection)) {
                networkSelection =
                        sp.getString(PhoneBase.NETWORK_SELECTION_KEY, "");
            }

            if (DBG) log("updateNetworkSelection()..." + "state = " +
                    serviceState + " new network " + networkSelection);

            if (serviceState == ServiceState.STATE_OUT_OF_SERVICE
                    && !TextUtils.isEmpty(networkSelection)) {
                if (!mSelectedUnavailableNotify) {
                    showNetworkSelection(networkSelection);
                    mSelectedUnavailableNotify = true;
                }
            } else {
                if (mSelectedUnavailableNotify) {
                    cancelNetworkSelection();
                    mSelectedUnavailableNotify = false;
                }
            }
        }
    }


    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
