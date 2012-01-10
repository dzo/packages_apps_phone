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

import java.util.List;

import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;

import android.content.Context;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * Phone app module that listens for phone state changes and various other
 * events from the telephony layer, and triggers any resulting UI behavior
 * (like starting the Ringer and Incoming Call UI, playing in-call tones,
 * updating notifications, writing call log entries, etc.)
 */
public class MSimCallNotifier extends CallNotifier {
    private static final String LOG_TAG = "MSimCallNotifier";
    private static final boolean DBG =
            (PhoneApp.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    private static final boolean VDBG = (PhoneApp.DBG_LEVEL >= 2);

    /**
     * Initialize the singleton CallNotifier instance.
     * This is only done once, at startup, from PhoneApp.onCreate().
     */
    /* package */ static MSimCallNotifier init(PhoneApp app, Phone phone, Ringer ringer,
                                           BluetoothHandsfree btMgr, CallLogAsync callLog) {
        synchronized (MSimCallNotifier.class) {
            if (sInstance == null) {
                sInstance = new MSimCallNotifier(app, phone, ringer, btMgr, callLog);
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return (MSimCallNotifier) sInstance;
        }
    }

    /** Private constructor; @see init() */
    private MSimCallNotifier(PhoneApp app, Phone phone, Ringer ringer,
                         BluetoothHandsfree btMgr, CallLogAsync callLog) {
        super(app, phone, ringer, btMgr, callLog);
        TelephonyManager telephonyManager = (TelephonyManager)app.mContext.getSystemService(
                Context.TELEPHONY_SERVICE);
        for (int i = 0; i < TelephonyManager.getDefault().getPhoneCount(); i++) {
            telephonyManager.listen(getPhoneStateListener(i),
                    PhoneStateListener.LISTEN_MESSAGE_WAITING_INDICATOR
                    | PhoneStateListener.LISTEN_CALL_FORWARDING_INDICATOR);
        }
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case PHONE_MWI_CHANGED:
                Phone phone = (Phone)msg.obj;
                onMwiChanged(mApplication.phone.getMessageWaitingIndicator(), phone);
                break;
            default:
                 super.handleMessage(msg);
        }
    }

    /**
     * Implemented for CallerInfoAsyncQuery.OnQueryCompleteListener interface.
     * refreshes the CallCard data when it called.  If called with this
     * class itself, it is assumed that we have been waiting for the ringtone
     * and direct to voicemail settings to update.
     */
    @Override
    public void onQueryComplete(int token, Object cookie, CallerInfo ci) {
        if (cookie instanceof Long) {
            if (VDBG) log("CallerInfo query complete, posting missed call notification");

            ((MSimNotificationMgr)(mApplication.notificationMgr)).notifyMissedCall(ci.name,
                    ci.phoneNumber, ci.phoneLabel, ((Long) cookie).longValue(),
                    mCM.getFirstActiveRingingCall().getPhone().getSubscription());;
        } else if (cookie instanceof CallNotifier) {
            if (VDBG) log("CallerInfo query complete (for CallNotifier), "
                          + "updating state for incoming call..");

            // get rid of the timeout messages
            removeMessages(RINGER_CUSTOM_RINGTONE_QUERY_TIMEOUT);

            boolean isQueryExecutionTimeOK = false;
            synchronized (mCallerInfoQueryStateGuard) {
                if (mCallerInfoQueryState == CALLERINFO_QUERYING) {
                    mCallerInfoQueryState = CALLERINFO_QUERY_READY;
                    isQueryExecutionTimeOK = true;
                }
            }
            //if we're in the right state
            if (isQueryExecutionTimeOK) {

                // send directly to voicemail.
                if (ci.shouldSendToVoicemail) {
                    if (DBG) log("send to voicemail flag detected. hanging up.");
                    PhoneUtils.hangupRingingCall(mCM.getFirstActiveRingingCall());
                    return;
                }

                // set the ringtone uri to prepare for the ring.
                if (ci.contactRingtoneUri != null) {
                    if (DBG) log("custom ringtone found, setting up ringer.");
                    Ringer r = ((CallNotifier) cookie).mRinger;
                    r.setCustomRingtoneUri(ci.contactRingtoneUri);
                }
                // ring, and other post-ring actions.
                onCustomRingQueryComplete();
            }
        }
    }

    private void onMwiChanged(boolean visible, Phone phone) {
        if (VDBG) log("onMwiChanged(): " + visible);

        // "Voicemail" is meaningless on non-voice-capable devices,
        // so ignore MWI events.
        if (!PhoneApp.sVoiceCapable) {
            // ...but still log a warning, since we shouldn't have gotten this
            // event in the first place!
            // (PhoneStateListener.LISTEN_MESSAGE_WAITING_INDICATOR events
            // *should* be blocked at the telephony layer on non-voice-capable
            // capable devices.)
            Log.w(LOG_TAG, "Got onMwiChanged() on non-voice-capable device! Ignoring...");
            return;
        }

        ((MSimNotificationMgr)mApplication.notificationMgr).updateMwi(visible, phone);
    }

    /**
     * Posts a delayed PHONE_MWI_CHANGED event, to schedule a "retry" for a
     * failed NotificationMgr.updateMwi() call.
     */
    /* package */
    void sendMwiChangedDelayed(long delayMillis, Phone phone) {
        Message message = Message.obtain(this, PHONE_MWI_CHANGED, phone);
        sendMessageDelayed(message, delayMillis);
    }

    protected void onCfiChanged(boolean visible, int subscription) {
        if (VDBG) log("onCfiChanged(): " + visible);
        ((MSimNotificationMgr)mApplication.notificationMgr).updateCfi(visible, subscription);
    }

    /**
     * Helper function used to show a missed call notification.
     */
    @Override
    void showMissedCallNotification(Connection c, final long date) {
        PhoneUtils.CallerInfoToken info =
            PhoneUtils.startGetCallerInfo(mApplication.mContext, c, this, Long.valueOf(date));
        if (info != null) {
            // at this point, we've requested to start a query, but it makes no
            // sense to log this missed call until the query comes back.
            if (VDBG) log("showMissedCallNotification: Querying for CallerInfo on missed call...");
            if (info.isFinal) {
                // it seems that the query we have actually is up to date.
                // send the notification then.
                CallerInfo ci = info.currentInfo;

                // Check number presentation value; if we have a non-allowed presentation,
                // then display an appropriate presentation string instead as the missed
                // call.
                String name = ci.name;
                String number = ci.phoneNumber;
                if (ci.numberPresentation == Connection.PRESENTATION_RESTRICTED) {
                    name = mApplication.mContext.getString(R.string.private_num);
                } else if (ci.numberPresentation != Connection.PRESENTATION_ALLOWED) {
                    name = mApplication.mContext.getString(R.string.unknown);
                } else {
                    number = PhoneUtils.modifyForSpecialCnapCases(mApplication.mContext,
                            ci, number, ci.numberPresentation);
                }
                ((MSimNotificationMgr)mApplication.notificationMgr).notifyMissedCall(name, number,
                        ci.phoneLabel, date, c.getCall().getPhone().getSubscription());
            }
        } else {
            // getCallerInfo() can return null in rare cases, like if we weren't
            // able to get a valid phone number out of the specified Connection.
            Log.w(LOG_TAG, "showMissedCallNotification: got null CallerInfo for Connection " + c);
        }
    }

    private PhoneStateListener getPhoneStateListener(int sub) {
        Log.d(LOG_TAG, "getPhoneStateListener: SUBSCRIPTION == " + sub);

        PhoneStateListener mPhoneStateListener = new PhoneStateListener(sub) {
            @Override
            public void onMessageWaitingIndicatorChanged(boolean mwi) {
                // mSubscription is a data member of PhoneStateListener class.
                // Each subscription is associated with one PhoneStateListener.
                onMwiChanged(mwi, PhoneApp.getInstance().getPhone(mSubscription));
            }

            @Override
            public void onCallForwardingIndicatorChanged(boolean cfi) {
                onCfiChanged(cfi, mSubscription);
            }
        };
        return mPhoneStateListener;
    }

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
