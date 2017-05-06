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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncTask;
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

import static java.util.regex.Pattern.DOTALL;

public class DBRoulette extends Activity {
    private static final String TAG = "DBRoulette";
    private static final String APP_KEY = "h1hbkipv02vmg9k";
    private static final String APP_SECRET = "r8sadyhrpw0w57u";
    private static final String ACCOUNT_PREFS_NAME = "prefs";
    private static final String ACCESS_KEY_NAME = "ACCESS_KEY";
    private static final String ACCESS_SECRET_NAME = "ACCESS_SECRET";
    private static final String ORG_PREFS_NAME = "org_prefs";
    private static final String ORG_FILE_REV_NAME = "org_hash";
    private static final String ORG_FILE_CONTENT_NAME = "org_content";
    private static final String ORG_FILE_LAST_CAPTURED_NAME = "org_last_captured";
    private static final String ORG_PATH = "/life.org";
    private static final String TEMP_FILE_NAME = "/temp.txt";

    private class OrgData {
        String rev;
        String fileContent;
        String lastCaptured;
    };

    DropboxAPI<AndroidAuthSession> mApi;
    private boolean mLoggedIn;
    private OrgData mOrgData;

    // Android widgets
    private Button mSubmit;
    private LinearLayout mDisplay;
    private Button mCaptureButton;
    private EditText mOrgDailyEdit;
    private EditText mCaptureTitle;
    private EditText mCaptureContent;
    private ProgressDialog mProgressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mApi = new DropboxAPI<>(buildSession());

        setContentView(R.layout.main);

        checkAppKeySetup();

        mOrgData = loadOrgFileData();

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

        mOrgDailyEdit = (EditText)findViewById(R.id.org_daily_edit);
        mOrgDailyEdit.setText("...\n" + getOrgDaily(mOrgData.fileContent));
        mCaptureTitle = (EditText)findViewById(R.id.capture_title);
        mCaptureContent = (EditText)findViewById(R.id.capture_content);

        mCaptureButton = (Button)findViewById(R.id.capture_to_org_button);
        mCaptureButton.setOnClickListener(
            new OnClickListener() {
                public void onClick(View view) {
                    final String captureTitle = mCaptureTitle.getText().toString();
                    final String captureContent = mCaptureContent.getText().toString();
                    final String orgFileRev = mOrgData.rev;
                    final String orgFileContent = mOrgData.fileContent;

                    new UpdateOrgContentTask().execute(
                            captureTitle, captureContent, orgFileRev, orgFileContent);
                }
            });

        setLoggedIn(mApi.getSession().isLinked());

        if (mLoggedIn) {
            final String orgFileRev = mOrgData.rev;
            final String orgLastCaptured = mOrgData.lastCaptured;
            new SyncOrgContentTask().execute(orgFileRev, orgLastCaptured);
        }
    }

    private void initEditTexts(final Intent intent, final String orgLastCaptured,
                               EditText editTitle, EditText editContent) {
        final String action = intent.getAction();
        final String type = intent.getType();
        String title = "";
        String content = "";

        if (Intent.ACTION_SEND.equals(action))
        {
            if ("text/plain".equals(type))
            {
                title = intent.getStringExtra(Intent.EXTRA_SUBJECT);
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

                content = intent.getStringExtra(Intent.EXTRA_TEXT);
            }
        } else {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clipData = clipboard.getPrimaryClip();
            ClipData.Item item = (clipData != null) ? clipData.getItemAt(0) : null;
            final String clipContent = (item != null) ? item.getText().toString() : null;
            if (orgLastCaptured.equals(clipContent) == false) {
                title = "clipboard";
                content = clipContent;
            }
        }

        editTitle.setText(title);
        editContent.setText(content);
    }

    private class SyncOrgContentTask extends AsyncTask<String, Void, Boolean> {
        private String mCurrRev = null;
        private String mCurrFileContent = null;
        private String mPrevLastCaptured = null;

        protected Boolean doInBackground(String... params) {
            final String prevRev = params[0];
            final String prevLastCaptured = params[1];
            final String path = getFilesDir() + TEMP_FILE_NAME;

            try {
                DropboxAPI.Entry entry = mApi.metadata(ORG_PATH, 1, null, false, null);
                if (prevRev.equals(entry.rev))
                    return false;

                mCurrRev = entry.rev;
                mPrevLastCaptured = prevLastCaptured;

                File file = new File(path);
                FileOutputStream outputStream = new FileOutputStream(file);
                mApi.getFile(ORG_PATH, null, outputStream, null);

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(new FileInputStream(file)));
                StringBuilder sb = new StringBuilder();
                String line = null;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                    sb.append('\n');
                }
                mCurrFileContent = sb.toString();
            } catch (Exception e) {
                showToast(e.toString());
                return false;
            }
            return true;
        }

        protected void onPostExecute(Boolean result){
            if (result == false) {
                mOrgDailyEdit.setText(getOrgDaily(mOrgData.fileContent));
                return;
            }

            mOrgData = new OrgData();
            mOrgData.rev = mCurrRev;
            mOrgData.fileContent = mCurrFileContent;
            mOrgData.lastCaptured = mPrevLastCaptured;
            mOrgDailyEdit.setText(getOrgDaily(mOrgData.fileContent));
            storeOrgFileData(mOrgData);
        }
    }

    private class UpdateOrgContentTask extends AsyncTask<String, Integer, Boolean> {
        private String mRev = null;
        private String mFileContent = null;
        private String mLastCaptured = null;

        protected void onPreExecute(){
            mProgressDialog = new ProgressDialog(DBRoulette.this);
            mProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            mProgressDialog.setTitle("Loading...");
            mProgressDialog.show();
        }

        protected Boolean doInBackground(String... params) {
            final String captureTitle = params[0];
            final String captureContent = params[1];
            final String prevRev = params[2];
            final String prevFileContent = params[3];
            final String addedContent = createOrgAddedContent(captureTitle, captureContent);
            final String path = getFilesDir() + TEMP_FILE_NAME;

            if (captureTitle.length() == 0 || captureContent.length() == 0) {
                showToast("empty capture content.");
                return false;
            }

            try {
                String currRev;
                {
                    DropboxAPI.Entry entry = mApi.metadata(ORG_PATH, 1, null, false, null);
                    currRev = entry.rev;
                }

                File file = new File(path);
                {
                    FileOutputStream outputStream = new FileOutputStream(file);
                    if (currRev.equals(prevRev)) {
                        Log.i(TAG, "rev match. using cached data");
                        publishProgress(50);
                        outputStream.write(prevFileContent.getBytes());
                    } else {
                        Log.i(TAG, "rev mismatch. read file.");
                        mApi.getFile(ORG_PATH, null,
                            outputStream,
                            new ProgressListener() {
                                @Override
                                public void onProgress(long bytes, long total) {
                                    final int firstHalfPercent = (int) ((100 * bytes / total) / 2);
                                    publishProgress(firstHalfPercent);
                                }
                            });
                    }
                }
                {
                    FileOutputStream outputStream = new FileOutputStream(file, true);
                    outputStream.write(addedContent.getBytes());
                }
                {
                    FileInputStream inStream = new FileInputStream(file);
                    DropboxAPI.Entry response = mApi.putFile(ORG_PATH, inStream,
                            file.length(), currRev,
                            new ProgressListener() {
                                @Override
                                public void onProgress(long bytes, long total) {
                                    final int secondHalfPecent = (int)(50 + (100 * bytes / total) / 2);
                                    publishProgress(secondHalfPecent);
                                }
                            });
                    mRev = response.rev;
                }
                {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(new FileInputStream(file)));
                    StringBuilder sb = new StringBuilder();
                    String line = null;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                        sb.append('\n');
                    }
                    mFileContent = sb.toString();
                    mLastCaptured = captureContent;
                }
            } catch (Exception e) {
                showToast(e.toString());
                return false;
            }
            return true;
        }

        protected void onProgressUpdate(Integer... progress){
            final int percent = progress[0];
            mProgressDialog.setProgress(percent);
        }

        protected void onPostExecute(Boolean result){
            mProgressDialog.dismiss();

            if (result == false)
                return;

            mOrgData = new OrgData();
            mOrgData.rev = mRev;
            mOrgData.fileContent = mFileContent;
            mOrgData.lastCaptured = mLastCaptured;
            mOrgDailyEdit.setText(getOrgDaily(mOrgData.fileContent));
            storeOrgFileData(mOrgData);
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

    @Override
    protected void onResume() {
        super.onResume();
        AndroidAuthSession session = mApi.getSession();
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

        initEditTexts(getIntent(), mOrgData.lastCaptured, mCaptureTitle, mCaptureContent);
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

    private String createOrgAddedContent(String captureTitle, String captureContent) {
        SimpleDateFormat sdf = new SimpleDateFormat("[yyyy-MM.dd EEE]");

        String temp = "\n** ";
        temp += (captureTitle.length() != 0) ? captureTitle : "capture";
        temp += "\n   " + sdf.format(new Date());
        temp += "\n   ";
        temp += captureContent;

        return temp;
    }

    private void checkAppKeySetup() {
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

    private void storeOrgFileData(OrgData orgData) {
        SharedPreferences prefs = getSharedPreferences(ORG_PREFS_NAME, MODE_PRIVATE);
        Editor editor = prefs.edit();
        final String rev = orgData.rev;
        final String content = orgData.fileContent;
        final String lastCaptured = orgData.lastCaptured;
        editor.putString(ORG_FILE_REV_NAME, rev);
        editor.putString(ORG_FILE_CONTENT_NAME, content);
        editor.putString(ORG_FILE_LAST_CAPTURED_NAME, lastCaptured);
        editor.commit();
    }

    private OrgData loadOrgFileData(){
        OrgData orgData = new OrgData();
        SharedPreferences prefs = getSharedPreferences(ORG_PREFS_NAME, MODE_PRIVATE);
        orgData.rev = prefs.getString(ORG_FILE_REV_NAME, "");
        orgData.fileContent = prefs.getString(ORG_FILE_CONTENT_NAME, "");
        orgData.lastCaptured = prefs.getString(ORG_FILE_LAST_CAPTURED_NAME, "");
        return orgData;
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

    private String getOrgDaily(String orgContent){
        Pattern p = Pattern.compile("\\n(\\* .*?)\\n\\*", DOTALL);
        Matcher m = p.matcher(orgContent);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }
}
