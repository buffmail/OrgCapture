package com.dropbox.android.sample;

import android.app.ProgressDialog;
import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import com.dropbox.client2.DropboxAPI;
import com.dropbox.client2.ProgressListener;
import com.dropbox.client2.android.AndroidAuthSession;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.DOTALL;

public class OrgUtil {
    private static final String TAG = "OrgUtil";
    private static final String ORG_PATH = "/life.org";
    private static final String ORG_FIRST_ENTRY_PATTERN = "^(\\* .*?)\\n\\*";

    public static class OrgData {
        String fileFullContent;
        String rev;
    };

    public interface OnCompleteListener {
        void onComplete(OrgData data);
    }

    public static class GetOrgContentTask extends AsyncTask<String, Void, Boolean> {
        private String mCurrRev = null;
        private String mCurrFileContent = null;
        private String mPath;
        private DropboxAPI<AndroidAuthSession> mApi;
        private OnCompleteListener mOnCompleteListener;

        public GetOrgContentTask(String path, DropboxAPI<AndroidAuthSession> api, OnCompleteListener listener)
        {
            mPath = path;
            mApi = api;
            mOnCompleteListener = listener;
        }

        protected Boolean doInBackground(String... params) {
            final String prevRev = params[0];

            try {
                DropboxAPI.Entry entry = mApi.metadata(ORG_PATH, 1, null, false, null);
                if (prevRev.equals(entry.rev))
                    return false;

                mCurrRev = entry.rev;

                File file = new File(mPath);
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
                Log.d(TAG, e.toString());
                return false;
            }
            return true;
        }

        protected void onPostExecute(Boolean result){
            OrgData orgData = null;

            if (result) {
                orgData = new OrgData();
                orgData.rev = mCurrRev;
                orgData.fileFullContent = mCurrFileContent;
            }

            mOnCompleteListener.onComplete(orgData);
        }
    }

    public static class UpdateOrgContentTask extends AsyncTask<String, Integer, Boolean> {
        private String mRev = null;
        private String mFileContent = null;
        private String mPath;
        private Context mContext;
        private DropboxAPI<AndroidAuthSession> mApi;
        private OnCompleteListener mOnCompleteListener;
        private ProgressDialog mProgressDialog;

        public UpdateOrgContentTask(String path, DropboxAPI<AndroidAuthSession> api, Context context,
                                    OnCompleteListener listener){
            mPath = path;
            mApi = api;
            mContext = context;
            mOnCompleteListener = listener;
        }

        protected void onPreExecute(){
            mProgressDialog = new ProgressDialog(mContext);
            mProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            mProgressDialog.setTitle("Loading...");
            mProgressDialog.show();
        }

        protected Boolean doInBackground(String... params) {
            final String prevRev = params[0];
            final String newOrgFileFullContent = params[1];

            try {
                String currRev;
                {
                    DropboxAPI.Entry entry = mApi.metadata(ORG_PATH, 1, null, false, null);
                    currRev = entry.rev;
                }

                File file = new File(mPath);
                {
                    FileOutputStream outputStream = new FileOutputStream(file);
                    if (currRev.equals(prevRev)) {
                        Log.i(TAG, "rev match. using cached data");
                        outputStream.write(newOrgFileFullContent.getBytes());
                    } else {
                        Log.i(TAG, "rev mismatch.");
                        return false;
                    }
                }
                {
                    FileInputStream inStream = new FileInputStream(file);
                    DropboxAPI.Entry response = mApi.putFile(ORG_PATH, inStream,
                            file.length(), currRev,
                            new ProgressListener() {
                                @Override
                                public void onProgress(long bytes, long total) {
                                    final int percent = (int)(100 * bytes / total);
                                    publishProgress(percent);
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
                }
            } catch (Exception e) {
                Log.d(TAG, e.toString());
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

            OrgData orgData = null;
            if (result){
                orgData = new OrgData();
                orgData.rev = mRev;
                orgData.fileFullContent = mFileContent;
            }

            mOnCompleteListener.onComplete(orgData);
        }
    }

    public static String GetOrgDailyLog(String orgFullContent){
        Pattern p = Pattern.compile(ORG_FIRST_ENTRY_PATTERN, DOTALL);
        Matcher m = p.matcher(orgFullContent);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }

    public static String GetRevisedDailyLog(String orgFullContent, String newDailyLog){
        Pattern p = Pattern.compile(ORG_FIRST_ENTRY_PATTERN, DOTALL);
        Matcher m = p.matcher(orgFullContent);
        if (m.find()) {
            StringBuffer buf = new StringBuffer(orgFullContent);
            buf.replace(m.start(1), m.end(1), newDailyLog);
            return buf.toString();
        }
        return orgFullContent;
    }

    public static String CreateOrgAddedContent(String orgContent, String captureTitle, String captureContent) {
        SimpleDateFormat sdf = new SimpleDateFormat("[yyyy-MM.dd EEE]");

        String temp = "\n** ";
        temp += (captureTitle.length() != 0) ? captureTitle : "capture";
        temp += "\n   " + sdf.format(new Date());
        temp += "\n   ";
        temp += captureContent;

        return orgContent + temp;
    }

}
