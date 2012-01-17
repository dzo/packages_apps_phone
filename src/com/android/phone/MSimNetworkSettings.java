/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (c) 2011 Code Aurora Forum. All rights reserved.
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

import android.content.Intent;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.provider.Settings.SettingNotFoundException;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;

import static com.android.internal.telephony.MSimConstants.SUBSCRIPTION_KEY;

/**
 * List of Phone-specific settings screens.
 */
public class MSimNetworkSettings extends PreferenceActivity implements Preference.OnPreferenceChangeListener {

    // debug data
    private static final String LOG_TAG = "MSimNetworkSettings";
    private static final boolean DBG = true;
    public static final int REQUEST_CODE_EXIT_ECM = 17;

    //String keys for preference lookup
    private static final String BUTTON_PREFERED_NETWORK_MODE = "preferred_network_mode_key";

    static final int preferredNetworkMode = Phone.PREFERRED_NT_MODE;

    //UI objects
    private ListPreference mButtonPreferredNetworkMode;

    private static final String iface = "rmnet0"; //TODO: this will go away

    private Phone mPhone;
    private MyHandler mHandler;
    private int mSubscription;

    //GsmUmts options and Cdma options
    GsmUmtsOptions mGsmUmtsOptions;
    CdmaOptions mCdmaOptions;

    private Preference mClickedPreference;


    /**
     * Invoked on each preference click in this hierarchy, overrides
     * PreferenceActivity's implementation.  Used to make sure we track the
     * preference click events.
     */
    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        /** TODO: Refactor and get rid of the if's using subclasses */
        if (mGsmUmtsOptions != null &&
                mGsmUmtsOptions.preferenceTreeClick(preference) == true) {
            return true;
        } else if (mCdmaOptions != null &&
                   mCdmaOptions.preferenceTreeClick(preference) == true) {
            if (Boolean.parseBoolean(
                    SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE))) {

                mClickedPreference = preference;

                // In ECM mode launch ECM app dialog
                startActivityForResult(
                    new Intent(TelephonyIntents.ACTION_SHOW_NOTICE_ECM_BLOCK_OTHERS, null),
                    REQUEST_CODE_EXIT_ECM);
            }
            return true;
        } else if (preference == mButtonPreferredNetworkMode) {
            //displays the value taken from the Settings.System
            int settingsNetworkMode = getPreferredNetworkMode();
            mButtonPreferredNetworkMode.setValue(Integer.toString(settingsNetworkMode));
            return true;
        } else {
            // if the button is anything but the simple toggle preference,
            // we'll need to disable all preferences to reject all click
            // events until the sub-activity's UI comes up.
            preferenceScreen.setEnabled(false);
            // Let the intents be launched by the Preference manager
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        PhoneApp app = PhoneApp.getInstance();
        addPreferencesFromResource(R.xml.msim_network_settings);

        mSubscription = getIntent().getIntExtra(SUBSCRIPTION_KEY, app.getDefaultSubscription());
        log("Settings onCreate subscription =" + mSubscription);
        mPhone = app.getPhone(mSubscription);
        mHandler = new MyHandler();

        //get UI object references
        PreferenceScreen prefSet = getPreferenceScreen();

        mButtonPreferredNetworkMode = (ListPreference) prefSet.findPreference(
                BUTTON_PREFERED_NETWORK_MODE);

        boolean isLteOnCdma = mPhone.getLteOnCdmaMode() == Phone.LTE_ON_CDMA_TRUE;
        if (getResources().getBoolean(R.bool.world_phone) == true) {

            if (SystemProperties.getBoolean("ro.monkey", false)) {
                prefSet.removePreference(mButtonPreferredNetworkMode);
            } else {
                // set the listener for the mButtonPreferredNetworkMode list
                // preference so we can issue change Preferred Network Mode.
                mButtonPreferredNetworkMode.setOnPreferenceChangeListener(this);

                //Get the networkMode from Settings.System and displays it
                int settingsNetworkMode = android.provider.Settings.Secure.getInt(mPhone.getContext().
                        getContentResolver(),android.provider.Settings.Secure.PREFERRED_NETWORK_MODE,
                        preferredNetworkMode);
                mButtonPreferredNetworkMode.setValue(Integer.toString(settingsNetworkMode));
                mCdmaOptions = new CdmaOptions(this, prefSet, mPhone);
                mGsmUmtsOptions = new GsmUmtsOptions(this, prefSet, mSubscription);
            }
        } else {
            if (!isLteOnCdma) {
                prefSet.removePreference(mButtonPreferredNetworkMode);
            }
            int phoneType = mPhone.getPhoneType();
            if (phoneType == Phone.PHONE_TYPE_CDMA) {
                mCdmaOptions = new CdmaOptions(this, prefSet, mPhone);
                if (isLteOnCdma) {
                    mButtonPreferredNetworkMode.setOnPreferenceChangeListener(this);
                    mButtonPreferredNetworkMode.setEntries(
                            R.array.preferred_network_mode_choices_lte);
                    mButtonPreferredNetworkMode.setEntryValues(
                            R.array.preferred_network_mode_values_lte);
                    int settingsNetworkMode = android.provider.Settings.Secure.getInt(
                            mPhone.getContext().getContentResolver(),
                            android.provider.Settings.Secure.PREFERRED_NETWORK_MODE,
                            preferredNetworkMode);
                    mButtonPreferredNetworkMode.setValue(
                            Integer.toString(settingsNetworkMode));
                }

            } else if (phoneType == Phone.PHONE_TYPE_GSM) {
                mGsmUmtsOptions = new GsmUmtsOptions(this, prefSet, mSubscription);
            } else {
                throw new IllegalStateException("Unexpected phone type: " + phoneType);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // upon resumption from the sub-activity, make sure we re-enable the
        // preferences.
        getPreferenceScreen().setEnabled(true);
        if (mGsmUmtsOptions != null) mGsmUmtsOptions.enableScreen();

        if (getPreferenceScreen().findPreference(BUTTON_PREFERED_NETWORK_MODE) != null)  {
            mPhone.getPreferredNetworkType(mHandler.obtainMessage(
                    MyHandler.MESSAGE_GET_PREFERRED_NETWORK_TYPE));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    /**
     * Implemented to support onPreferenceChangeListener to look for preference
     * changes specifically on CLIR.
     *
     * @param preference is the preference to be changed, should be mButtonCLIR.
     * @param objValue should be the value of the selection, NOT its localized
     * display value.
     */
    public boolean onPreferenceChange(Preference preference, Object objValue) {
        if (preference == mButtonPreferredNetworkMode) {
            //NOTE onPreferenceChange seems to be called even if there is no change
            //Check if the button value is changed from the System.Setting
            mButtonPreferredNetworkMode.setValue((String) objValue);
            int buttonNetworkMode;
            buttonNetworkMode = Integer.valueOf((String) objValue).intValue();
            int settingsNetworkMode = getPreferredNetworkMode();
            if (buttonNetworkMode != settingsNetworkMode) {
                int modemNetworkMode = buttonNetworkMode;
                // if new mode is invalid set mode to default preferred
                if ((modemNetworkMode < Phone.NT_MODE_WCDMA_PREF)
                        || (modemNetworkMode > Phone.NT_MODE_LTE_ONLY)) {
                    modemNetworkMode = Phone.PREFERRED_NT_MODE;
                }

                // If button has no valid selection && setting is LTE ONLY
                // mode, let the setting stay in LTE ONLY mode. UI is not
                // supported but LTE ONLY mode could be used in testing.
                if ((modemNetworkMode == Phone.PREFERRED_NT_MODE) &&
                    (settingsNetworkMode == Phone.NT_MODE_LTE_ONLY)) {
                    return true;
                }

                UpdatePreferredNetworkModeSummary(buttonNetworkMode);
                setPreferredNetworkMode(buttonNetworkMode);
                //Set the modem network mode
                mPhone.setPreferredNetworkType(modemNetworkMode, mHandler
                        .obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
            }
        }

        // always let the preference setting proceed.
        return true;
    }

    private int getPreferredNetworkMode() {
        int nwMode;
        try {
            nwMode = android.provider.Settings.Secure.getIntAtIndex(
                    mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Secure.PREFERRED_NETWORK_MODE,
                    mSubscription);
        } catch (SettingNotFoundException snfe) {
            log("getPreferredNetworkMode: Could not find PREFERRED_NETWORK_MODE!!!");
            nwMode = preferredNetworkMode;
        }
        return nwMode;
    }

    private void setPreferredNetworkMode(int nwMode) {
        android.provider.Settings.Secure.putIntAtIndex(
                    mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Secure.PREFERRED_NETWORK_MODE,
                    mSubscription, nwMode);
        // Refresh the GSM UMTS options UI on network mode change
        if (mGsmUmtsOptions != null) mGsmUmtsOptions.enableScreen();
    }

    private class MyHandler extends Handler {

        private static final int MESSAGE_GET_PREFERRED_NETWORK_TYPE = 0;
        private static final int MESSAGE_SET_PREFERRED_NETWORK_TYPE = 1;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_GET_PREFERRED_NETWORK_TYPE:
                    handleGetPreferredNetworkTypeResponse(msg);
                    break;

                case MESSAGE_SET_PREFERRED_NETWORK_TYPE:
                    handleSetPreferredNetworkTypeResponse(msg);
                    break;
            }
        }

        private void handleGetPreferredNetworkTypeResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                int modemNetworkMode = ((int[])ar.result)[0];

                if (DBG) {
                    log ("handleGetPreferredNetworkTypeResponse: modemNetworkMode = " +
                            modemNetworkMode);
                }

                int settingsNetworkMode = getPreferredNetworkMode();
                if (DBG) {
                    log("handleGetPreferredNetworkTypeReponse: settingsNetworkMode = " +
                            settingsNetworkMode);
                }

                //check that modemNetworkMode is from an accepted value
                if (modemNetworkMode == Phone.NT_MODE_WCDMA_PREF ||
                        modemNetworkMode == Phone.NT_MODE_GSM_ONLY ||
                        modemNetworkMode == Phone.NT_MODE_WCDMA_ONLY ||
                        modemNetworkMode == Phone.NT_MODE_GSM_UMTS ||
                        modemNetworkMode == Phone.NT_MODE_CDMA ||
                        modemNetworkMode == Phone.NT_MODE_CDMA_NO_EVDO ||
                        modemNetworkMode == Phone.NT_MODE_EVDO_NO_CDMA ||
                        modemNetworkMode == Phone.NT_MODE_GLOBAL ||
                        modemNetworkMode == Phone.NT_MODE_LTE_CDMA_AND_EVDO ||
                        modemNetworkMode == Phone.NT_MODE_LTE_GSM_WCDMA ||
                        modemNetworkMode == Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA ||
                        modemNetworkMode == Phone.NT_MODE_LTE_ONLY) {
                    if (DBG) {
                        log("handleGetPreferredNetworkTypeResponse: if 1: modemNetworkMode = " +
                                modemNetworkMode);
                    }

                    //check changes in modemNetworkMode and updates settingsNetworkMode
                    if (modemNetworkMode != settingsNetworkMode) {
                        if (DBG) {
                            log("handleGetPreferredNetworkTypeResponse: if 2: " +
                                    "modemNetworkMode != settingsNetworkMode");
                        }

                        settingsNetworkMode = modemNetworkMode;

                        if (DBG) { log("handleGetPreferredNetworkTypeResponse: if 2: " +
                                "settingsNetworkMode = " + settingsNetworkMode);
                        }

                        //changes the Settings.System accordingly to modemNetworkMode
                        setPreferredNetworkMode(settingsNetworkMode);
                    }

                    UpdatePreferredNetworkModeSummary(modemNetworkMode);
                    // changes the mButtonPreferredNetworkMode accordingly to modemNetworkMode
                    mButtonPreferredNetworkMode.setValue(Integer.toString(modemNetworkMode));
                } else if (modemNetworkMode == Phone.NT_MODE_LTE_ONLY) {
                    // LTE Only mode not yet supported on UI, but could be used for testing
                    if (DBG) log("handleGetPreferredNetworkTypeResponse: lte only: no action");
                } else {
                    if (DBG) log("handleGetPreferredNetworkTypeResponse: else: reset to default");
                    resetNetworkModeToDefault();
                }
            }
        }

        private void handleSetPreferredNetworkTypeResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                int networkMode = Integer.valueOf(
                        mButtonPreferredNetworkMode.getValue()).intValue();
                setPreferredNetworkMode(networkMode);
            } else {
                mPhone.getPreferredNetworkType(obtainMessage(MESSAGE_GET_PREFERRED_NETWORK_TYPE));
            }
            // Update '2GOnly checkbox' based on recent preferred network type selection.
            Use2GOnlyCheckBoxPreference.updatePhone(mPhone);

        }

        private void resetNetworkModeToDefault() {
            //set the mButtonPreferredNetworkMode
            mButtonPreferredNetworkMode.setValue(Integer.toString(preferredNetworkMode));
            //set the Settings.System
            setPreferredNetworkMode(preferredNetworkMode);
            //Set the Modem
            mPhone.setPreferredNetworkType(preferredNetworkMode,
                    this.obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
        }
    }

    private void UpdatePreferredNetworkModeSummary(int NetworkMode) {
        switch(NetworkMode) {
            case Phone.NT_MODE_WCDMA_PREF:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_wcdma_perf_summary);
                break;
            case Phone.NT_MODE_GSM_ONLY:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_gsm_only_summary);
                break;
            case Phone.NT_MODE_WCDMA_ONLY:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_wcdma_only_summary);
                break;
            case Phone.NT_MODE_GSM_UMTS:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_gsm_wcdma_summary);
                break;
            case Phone.NT_MODE_CDMA:
                switch (mPhone.getLteOnCdmaMode()) {
                    case Phone.LTE_ON_CDMA_TRUE:
                        mButtonPreferredNetworkMode.setSummary(
                            R.string.preferred_network_mode_cdma_summary);
                    break;
                    case Phone.LTE_ON_CDMA_FALSE:
                    default:
                        mButtonPreferredNetworkMode.setSummary(
                            R.string.preferred_network_mode_cdma_evdo_summary);
                        break;
                }
                break;
            case Phone.NT_MODE_CDMA_NO_EVDO:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_cdma_only_summary);
                break;
            case Phone.NT_MODE_EVDO_NO_CDMA:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_evdo_only_summary);
                break;
            case Phone.NT_MODE_LTE_ONLY:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_lte_summary);
                break;
            case Phone.NT_MODE_LTE_GSM_WCDMA:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_lte_gsm_wcdma_summary);
                break;
            case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_lte_cdma_evdo_summary);
                break;
            case Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_global_summary);
                break;
            case Phone.NT_MODE_GLOBAL:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_cdma_evdo_gsm_wcdma_summary);
                break;
            default:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_global_summary);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch(requestCode) {
        case REQUEST_CODE_EXIT_ECM:
            Boolean isChoiceYes =
                data.getBooleanExtra(EmergencyCallbackModeExitDialog.EXTRA_EXIT_ECM_RESULT, false);
            if (isChoiceYes) {
                // If the phone exits from ECM mode, show the CDMA Options
                mCdmaOptions.showDialog(mClickedPreference);
            } else {
                // do nothing
            }
            break;

        default:
            break;
        }
    }

    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }

}
