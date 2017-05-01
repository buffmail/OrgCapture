/*
 * Copyright (c) 2010-11 Dropbox, Inc.
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package com.dropbox.android.sample;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.dropbox.client2.DropboxAPI;
import com.dropbox.client2.ProgressListener;
import com.dropbox.client2.android.AndroidAuthSession;
import com.dropbox.client2.android.AuthActivity;
import com.dropbox.client2.session.AccessTokenPair;
import com.dropbox.client2.session.AppKeyPair;

public class DBRoulette extends Activity {
    private static final String TAG = "DBRoulette";
    private static final String APP_KEY = "h1hbkipv02vmg9k";
    private static final String APP_SECRET = "r8sadyhrpw0w57u";
    private static final String ACCOUNT_PREFS_NAME = "prefs";
    private static final String ACCESS_KEY_NAME = "ACCESS_KEY";
    private static final String ACCESS_SECRET_NAME = "ACCESS_SECRET";
    private static final String ORG_PATH = "life.org";

    DropboxAPI<AndroidAuthSession> mApi;
    private boolean mLoggedIn;

    // Android widgets
    private Button mSubmit;
    private LinearLayout mDisplay;
    private Button mCaptureButton;
    private EditText mCaptureTitle;
    private EditText mCaptureContent;
    private ProgressDialog mProgressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AndroidAuthSession session = buildSession();
        mApi = new DropboxAPI<AndroidAuthSession>(session);

        setContentView(R.layout.main);

        checkAppKeySetup();

        mSubmit = (Button)findViewById(R.id.auth_button);
        mSubmit.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                if (mLoggedIn) {
                    logOut();
                } else {
                        mApi.getSession().startOAuth2Authentication(DBRoulette.this);
                }
            }
        });

        mDisplay = (LinearLayout)findViewById(R.id.logged_in_display);

        mCaptureButton = (Button)findViewById(R.id.capture_to_org_button);
        mCaptureTitle = (EditText)findViewById(R.id.capture_title);
        mCaptureContent = (EditText)findViewById(R.id.capture_content);

        mCaptureButton.setOnClickListener(createCaptureClickListener(mCaptureTitle, mCaptureContent));

        setLoggedIn(mApi.getSession().isLinked());

        Intent intent = getIntent();
        final String action = intent.getAction();
        final String type = intent.getType();

        if (Intent.ACTION_SEND.equals(action) && type != null)
        {
            if ("text/plain".equals(type))
            {
                final String text = intent.getStringExtra(Intent.EXTRA_TEXT);
                String title = intent.getStringExtra(Intent.EXTRA_SUBJECT);
                if (title == null)
                    title = intent.getStringExtra(Intent.EXTRA_TITLE);
                if (title == null) {
                    ComponentName name = getCallingActivity();
                    if (name != null)
                        title = name.getShortClassName();
                }
                if (title == null){
                    title = "capture";
                }

                mCaptureTitle.setText(title);
                mCaptureContent.setText(text);
            }
        }
    }

    @Override
    protected void onDestroy(){
        if (mProgressDialog != null) {
            mProgressDialog.dismiss();
            mProgressDialog = null;
        }
        super.onDestroy();
    }

    private OnClickListener createCaptureClickListener(EditText captureTitle, EditText captureContent)
    {
        class MyClickListener implements OnClickListener
        {
            private EditText mTitle;
            private EditText mContent;

            public MyClickListener(EditText title, EditText content){
                mTitle = title;
                mContent = content;
            }

            public void onClick(View v) {
                final String captureTitle = mTitle.getText().toString();
                final String captureContent = mContent.getText().toString();
                if (captureTitle.length() == 0 || captureContent.length() == 0) {
                    showToast("empty capture content.");
                    return;
                }

                SimpleDateFormat sdf = new SimpleDateFormat("[yyyy-MM.dd EEE]");

                String temp = "\n** ";
                temp += (captureTitle.length() != 0) ? captureTitle : "capture";
                temp += "\n   " + sdf.format(new Date());
                temp += "\n   ";
                temp += captureContent;
                final String content = temp;
                final String path = getFilesDir() + "/temp.txt";

                mProgressDialog = new ProgressDialog(DBRoulette.this);
                mProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                mProgressDialog.setTitle("Loading...");
                mProgressDialog.show();

                class MyProgressListener extends ProgressListener {
                    private int mBasePercent;
                    private int mTopPercent;

                    public MyProgressListener(int basePercent, int topPercent){
                        mBasePercent = basePercent;
                        mTopPercent = topPercent;
                    }

                    @Override
                    public long progressInterval(){
                        return 100;
                    }

                    @Override
                    public void onProgress(long bytes, long total) {
                        int percent = mBasePercent +
                                (int)((mTopPercent - mBasePercent) * bytes / (float)total);
                        mProgressDialog.setProgress(percent);
                    }
                };

                new Thread(){
                    public void run(){
                        try {
                            String parentRev = null;
                            File file = new File(path);
                            {
                                FileOutputStream outputStream = new FileOutputStream(file);
                                DropboxAPI.DropboxFileInfo info = mApi.getFile(ORG_PATH, null,
                                        outputStream, new MyProgressListener(0, 50));
                                parentRev = info.getMetadata().rev;

                                outputStream = new FileOutputStream(file, true);
                                outputStream.write(content.getBytes());
                            }
                            {
                                FileInputStream inStream = new FileInputStream(file);
                                DropboxAPI.Entry response = mApi.putFile(ORG_PATH, inStream,
                                        file.length(), parentRev,
                                        new MyProgressListener(50, 100));
                                showToast("Uploaded file rev : " + response.rev);
                            }
                        } catch (Exception e) {
                            mProgressDialog.dismiss();
                            showToast(e.toString());
                        }
                        mProgressDialog.dismiss();
                    }
                }.start();
            }
        }

        return new MyClickListener(captureTitle, captureContent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AndroidAuthSession session = mApi.getSession();

        // The next part must be inserted in the onResume() method of the
        // activity from which session.startAuthentication() was called, so
        // that Dropbox authentication completes properly.
        if (session.authenticationSuccessful()) {
            try {
                // Mandatory call to complete the auth
                session.finishAuthentication();

                // Store it locally in our app for later use
                storeAuth(session);
                setLoggedIn(true);
            } catch (IllegalStateException e) {
                showToast("Couldn't authenticate with Dropbox:" + e.getLocalizedMessage());
                Log.i(TAG, "Error authenticating", e);
            }
        }
    }
    private void logOut() {
        // Remove credentials from the session
        mApi.getSession().unlink();

        // Clear our stored keys
        clearKeys();
        // Change UI state to display logged out version
        setLoggedIn(false);
    }
    /**
     * Convenience function to change UI state based on being logged in
     */
    private void setLoggedIn(boolean loggedIn) {
    	mLoggedIn = loggedIn;
    	if (loggedIn) {
    		mSubmit.setText("Unlink from Dropbox");
            mDisplay.setVisibility(View.VISIBLE);
    	} else {
    		mSubmit.setText("Link with Dropbox");
            mDisplay.setVisibility(View.GONE);
    	}
    }

    private void checkAppKeySetup() {
        // Check to make sure that we have a valid app key
        if (APP_KEY.startsWith("CHANGE") || APP_SECRET.startsWith("CHANGE")) {
            showToast("You must apply for an app key and secret from developers.dropbox.com, "
                    + "and add them to the DBRoulette ap before trying it.");
            finish();
            return;
        }

        // Check if the app has set up its manifest properly.
        Intent testIntent = new Intent(Intent.ACTION_VIEW);
        String scheme = "db-" + APP_KEY;
        String uri = scheme + "://" + AuthActivity.AUTH_VERSION + "/test";
        testIntent.setData(Uri.parse(uri));
        PackageManager pm = getPackageManager();
        if (0 == pm.queryIntentActivities(testIntent, 0).size()) {
            showToast("URL scheme in your app's " +
                    "manifest is not set up correctly. You should have a " +
                    "com.dropbox.client2.android.AuthActivity with the " +
                    "scheme: " + scheme);
            finish();
        }
    }

    private void showToast(final String msg) {
        runOnUiThread(new Runnable(){
            public void run() {
                Toast error = Toast.makeText(DBRoulette.this, msg, Toast.LENGTH_LONG);
                error.show();
            }
        });
    }

    private void loadAuth(AndroidAuthSession session) {
        SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
        String key = prefs.getString(ACCESS_KEY_NAME, null);
        String secret = prefs.getString(ACCESS_SECRET_NAME, null);
        if (key == null || secret == null || key.length() == 0 || secret.length() == 0) return;

        if (key.equals("oauth2:")) {
            // If the key is set to "oauth2:", then we can assume the token is for OAuth 2.
            session.setOAuth2AccessToken(secret);
        } else {
            // Still support using old OAuth 1 tokens.
            session.setAccessTokenPair(new AccessTokenPair(key, secret));
        }
    }

    /**
     * Shows keeping the access keys returned from Trusted Authenticator in a local
     * store, rather than storing user name & password, and re-authenticating each
     * time (which is not to be done, ever).
     */
    private void storeAuth(AndroidAuthSession session) {
        // Store the OAuth 2 access token, if there is one.
        String oauth2AccessToken = session.getOAuth2AccessToken();
        if (oauth2AccessToken != null) {
            SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
            Editor edit = prefs.edit();
            edit.putString(ACCESS_KEY_NAME, "oauth2:");
            edit.putString(ACCESS_SECRET_NAME, oauth2AccessToken);
            edit.commit();
            return;
        }
        // Store the OAuth 1 access token, if there is one.  This is only necessary if
        // you're still using OAuth 1.
        AccessTokenPair oauth1AccessToken = session.getAccessTokenPair();
        if (oauth1AccessToken != null) {
            SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
            Editor edit = prefs.edit();
            edit.putString(ACCESS_KEY_NAME, oauth1AccessToken.key);
            edit.putString(ACCESS_SECRET_NAME, oauth1AccessToken.secret);
            edit.commit();
            return;
        }
    }

    private void clearKeys() {
        SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
        Editor edit = prefs.edit();
        edit.clear();
        edit.commit();
    }

    private AndroidAuthSession buildSession() {
        AppKeyPair appKeyPair = new AppKeyPair(APP_KEY, APP_SECRET);

        AndroidAuthSession session = new AndroidAuthSession(appKeyPair);
        loadAuth(session);
        return session;
    }
}
