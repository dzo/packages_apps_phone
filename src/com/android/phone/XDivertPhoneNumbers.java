/*
 * Copyright (c) 2012 Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of Code Aurora Forum, Inc. nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.phone;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Selection;
import android.text.Spannable;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

// This class handles the user entry for phone numbers.

public class XDivertPhoneNumbers extends Activity {
    private static final String LOG_TAG = "XDivertPhoneNumbers";
    private static final boolean DBG = false;

    private static final int SUB1 = 0;
    private static final int SUB2 = 1;

    private EditText mSub1Line1Number;
    private EditText mSub2Line1Number;
    private String mSub1Number;
    private String mSub2Number;
    private Button mButton;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        Intent intent = getIntent();
        mSub1Number = intent.getStringExtra("Sub1_Line1Number");
        mSub2Number = intent.getStringExtra("Sub2_Line1Number");
        Log.d(LOG_TAG,"onCreate: sub1 line number = " + mSub1Number +
                "sub2 line number = " + mSub2Number);
        setContentView(R.layout.xdivert_phone_numbers);
        setupView();

    }

    private void setupView() {
        mSub1Line1Number = (EditText) findViewById(R.id.sub1_number);
        if (mSub1Line1Number != null) {
            mSub1Line1Number.setText(mSub1Number);
            mSub1Line1Number.setOnFocusChangeListener(mOnFocusChangeHandler);
            mSub1Line1Number.setOnClickListener(mClicked);
        }

        mSub2Line1Number = (EditText) findViewById(R.id.sub2_number);
        if (mSub2Line1Number != null) {
            mSub2Line1Number.setText(mSub2Number);
            mSub2Line1Number.setOnFocusChangeListener(mOnFocusChangeHandler);
            mSub2Line1Number.setOnClickListener(mClicked);
        }

        mButton = (Button) findViewById(R.id.button);
        if (mButton != null) {
            mButton.setOnClickListener(mClicked);
        }
    }

    private String getSub1Number() {
        return mSub1Line1Number.getText().toString();
    }

    private String getSub2Number() {
        return mSub2Line1Number.getText().toString();
    }

    private void processXDivert() {
        Intent intent  = new Intent();
        intent.setClass(this, XDivertSetting.class);
        Log.d(LOG_TAG,"OnSave: sub1 line number = " + getSub1Number() +
                "sub2 line number = " + getSub2Number());
        intent.putExtra("Sub1_Line1Number" ,getSub1Number());
        intent.putExtra("Sub2_Line1Number" ,getSub2Number());
        startActivity(intent);
    }

    private View.OnClickListener mClicked = new View.OnClickListener() {
        public void onClick(View v) {
            if (v == mSub1Line1Number) {
                mSub2Line1Number.requestFocus();
            } else if (v == mSub2Line1Number) {
                mButton.requestFocus();
            } else if (v == mButton) {
                if ((getSub1Number().length() == 0) || (getSub2Number().length() == 0)) {
                    Toast toast = Toast.makeText(getApplicationContext(),
                            R.string.xdivert_enternumber_error,
                            Toast.LENGTH_LONG);
                    toast.show();
                } else {
                    processXDivert();
                }
            }
        }
    };

    View.OnFocusChangeListener mOnFocusChangeHandler =
            new View.OnFocusChangeListener() {
        public void onFocusChange(View v, boolean hasFocus) {
            if (hasFocus) {
                TextView textView = (TextView) v;
                Selection.selectAll((Spannable) textView.getText());
            }
        }
    };
}
