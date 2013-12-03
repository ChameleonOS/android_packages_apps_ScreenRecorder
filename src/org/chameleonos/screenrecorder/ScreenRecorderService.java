/**
 * Copyright (c) 2013, The ChameleonOS Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.chameleonos.screenrecorder;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.media.MediaActionSound;
import android.media.MediaMetadataRetriever;
import android.media.screenrecorder.ScreenRecorder;
import android.media.screenrecorder.ScreenRecorder.ScreenRecorderCallbacks;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

public class ScreenRecorderService extends IntentService
        implements ScreenRecorderCallbacks {
    public static final String ACTION_NOTIFY_RECORD_SERVICE
            = "org.chameleonos.action.NOTIFY_RECORD_SERVICE";

    private static final String TAG = "ScreenRecorderService";
    private static final String RECORDER_FOLDER = "ScreenRecorder";
    private static final String RECORDER_PATH =
            Environment.getExternalStorageDirectory().getAbsolutePath()
                    + File.separator + RECORDER_FOLDER;

    private static final int NOTIFICATION_ID = 0xD34D;

    // videos will be saved using SCR_[date]_[time].mp4
    private static final String FILE_NAME_FORMAT = "SCR_%s.mp4";
    private static final String DATE_TIME_FORMAT = "yyyyMMdd_HHmmss";

    private static String sCurrentFileName;
    private static ScreenRecorder sScreenRecorder;
    private static MediaActionSound sActionSound = new MediaActionSound();

    public ScreenRecorderService() {
        super(TAG);
    }

    private void startOrStopRecording() {
        if (sScreenRecorder == null) {
            sScreenRecorder = ScreenRecorder.getInstance();
            sScreenRecorder.setScreenRecorderCallbacks(this);
        }
        final int state = sScreenRecorder.getState();
        if (state != ScreenRecorder.STATE_RECORDING) {
            startRecording();
        } else {
            sActionSound.play(MediaActionSound.STOP_VIDEO_RECORDING);
            sScreenRecorder.stop();
        }
    }

    private static String getVideoFileName() {
        final String dateTime;
        SimpleDateFormat sdf = new SimpleDateFormat(DATE_TIME_FORMAT);
        dateTime = sdf.format(new Date()).toString();

        return String.format(FILE_NAME_FORMAT, dateTime);
    }

    private void startRecording() {
        // make sure external storage is available first
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            postRecordingErrorNotification(getString(R.string.error_external_storage_unavailable));
            return;
        }

        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        final Display display = wm.getDefaultDisplay();
        final int rotation = display.getRotation();
        final ContentResolver resolver = getContentResolver();
        final Resources res = getResources();

        int[] dimensions = getVideoDimensions(res, display);
        int bitRate = Settings.System.getInt(getContentResolver(),
                Settings.System.SCREEN_RECORDER_BITRATE,
                res.getInteger(R.integer.config_screenRecorderFramerate));
        sScreenRecorder.init(rotation, dimensions[0], dimensions[1], bitRate, 0);
        File f = new File(RECORDER_PATH);
        if (!f.exists()) {
            if (!f.mkdir()) {
                Log.e(TAG, "Unable to create output directory " + RECORDER_PATH);
                postRecordingErrorNotification(
                        getString(R.string.error_unable_to_create_directory));
                return;
            }
        }
        sCurrentFileName = getVideoFileName();
        final String fileName = RECORDER_PATH + File.separator + sCurrentFileName;
        Log.d(TAG, "Start recording screen to " + fileName);
        try {
            if (!sScreenRecorder.start(fileName)) {
                postRecordingErrorNotification(getString(R.string.error_unable_to_start));
            }
        } catch (IllegalStateException e) {
            postRecordingErrorNotification(getString(R.string.error_unable_to_start));
        }
    }

    /* Screen recorder callbacks */
    @Override
    public void onRecordingStarted() {
        sActionSound.play(MediaActionSound.START_VIDEO_RECORDING);
        postRecordingNotification();
    }

    @Override
    public void onRecordingFinished() {
        stopForeground(true);
        postRecordingFinishedNotification();
    }

    @Override
    public void onRecordingError(String error) {
        postRecordingErrorNotification(error);
    }

    private void postRecordingNotification() {
        Intent intent = new Intent(ACTION_NOTIFY_RECORD_SERVICE);
        PendingIntent contentIntent = PendingIntent.getBroadcast(this, 0, intent, 0);
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification notice = new Notification.Builder(this)
                .setAutoCancel(false)
                .setOngoing(true)
                .setContentTitle(getString(R.string.notification_recording_title))
                .setContentText(getString(R.string.notification_recording_text))
                .setSmallIcon(R.drawable.ic_notify_screen_recorder)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(contentIntent)
                .build();
        nm.notify(NOTIFICATION_ID, notice);
    }

    private void postRecordingFinishedNotification() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(
                Uri.fromFile(new File(RECORDER_PATH + File.separator + sCurrentFileName)),
                "video/mp4");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, intent, 0);
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b = new Notification.Builder(this)
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentTitle(getString(R.string.notification_recording_finished_title))
                .setContentText(getString(R.string.notification_recording_finished_text,
                        sCurrentFileName))
                .setSmallIcon(R.drawable.ic_notify_screen_recorder)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(contentIntent);

        // try and grab a frame as a preview
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        mmr.setDataSource(RECORDER_PATH + File.separator + sCurrentFileName);
        Bitmap thumbnail = mmr.getFrameAtTime();
        if (thumbnail != null) {
            Notification.BigPictureStyle style =
                    new Notification.BigPictureStyle()
                            .bigPicture(thumbnail)
                    .setSummaryText(getString(R.string.notification_recording_finished_text,
                            sCurrentFileName));
            b.setStyle(style);
        }

        nm.notify(NOTIFICATION_ID, b.build());
    }

    private void postRecordingErrorNotification(String error) {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification notice = new Notification.Builder(this)
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentTitle(getString(R.string.notification_recording_error_title))
                .setContentText(getString(R.string.notification_recording_error_text, error))
                .setSmallIcon(R.drawable.ic_notify_screen_recorder_error)
                .setWhen(System.currentTimeMillis())
                .build();
        nm.notify(NOTIFICATION_ID, notice);
    }

    private int[] getVideoDimensions(Resources res, Display display) {
        String dimensionString = Settings.System.getString(getContentResolver(),
                Settings.System.SCREEN_RECORDER_OUTPUT_DIMENSIONS);
        if (TextUtils.isEmpty(dimensionString)) {
            dimensionString = res.getString(R.string.config_screenRecorderOutputDimensions);
        }
        int[] dimensions = parseDimensions(dimensionString);
        if (dimensions == null) {
            dimensions = new int[] {720, 1280};
        }

        // if rotation is Surface.ROTATION_0 and width>height swap
        final Point p = new Point();
        display.getRealSize(p);
        if (display.getRotation() == Surface.ROTATION_0 && (p.x > p.y)) {
            int tmp = dimensions[0];
            dimensions[0] = dimensions[1];
            dimensions[1] = tmp;
        }

        return dimensions;
    }

    private static int[] parseDimensions(String dimensions) {
        String[] tmp = dimensions.split("x");
        if (tmp.length < 2) return null;
        int[] dims = new int[2];
        try {
            dims[0] = Integer.valueOf(tmp[0]);
            dims[1] = Integer.valueOf(tmp[1]);
        } catch (NumberFormatException e) {
            return null;
        }

        return dims;
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (ACTION_NOTIFY_RECORD_SERVICE.equals(intent.getAction())) {
            startOrStopRecording();
        }
    }
}
