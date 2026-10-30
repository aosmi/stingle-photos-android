package org.stingle.photos.AsyncTasks;

import android.app.ProgressDialog;
import android.app.RecoverableSecurityException;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.IntentSender;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import androidx.appcompat.app.AppCompatActivity;

import org.stingle.photos.Files.FileManager;
import org.stingle.photos.GalleryActivity;
import org.stingle.photos.R;
import org.stingle.photos.Util.Helpers;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
// this piece of shit doesn't crash anymore and supports modern SDKs.
public class DeleteUrisAsyncTask extends AsyncTask<Void, Integer, Void> {

    private WeakReference<AppCompatActivity> activity;
    private ArrayList<Uri> uris;
    private FileManager.OnFinish onFinish;
    private ProgressDialog progress;
    private String errorMessage;

    public static ArrayDeque<Uri> urisToDelete = new ArrayDeque<>();

    public DeleteUrisAsyncTask(AppCompatActivity activity, ArrayList<Uri> uris, FileManager.OnFinish onFinish) {
        this.activity = new WeakReference<>(activity);
        this.uris = uris;
        this.onFinish = onFinish;
    }

    @Override
    protected void onPreExecute() {
        super.onPreExecute();
        AppCompatActivity myActivity = activity.get();
        if (myActivity == null) {
            return;
        }
        progress = Helpers.showProgressDialogWithBar(myActivity, myActivity.getString(R.string.deleting_files), null, uris.size(), new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialogInterface) {
                Helpers.releaseWakeLock(myActivity);
                DeleteUrisAsyncTask.this.cancel(false);
                onFinish.onFinish();
            }
        });
        Helpers.acquireWakeLock(myActivity);
    }

    @Override
    protected Void doInBackground(Void... params) {
        AppCompatActivity myActivity = activity.get();
        if (myActivity == null) {
            return null;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                List<Uri> mediaUris = new ArrayList<>();
                for (Uri uri : uris) {
                    Uri contentUri = getContentUri(myActivity, uri);
                    if (contentUri == null) {
                        errorMessage = myActivity.getString(R.string.delete_failed_desc);
                        continue;
                    }

                    String path = contentUri.getPath();
                    boolean isMediaCollection = "content".equalsIgnoreCase(contentUri.getScheme())
                            && "media".equals(contentUri.getAuthority())
                            && path != null
                            && (path.contains("/images/media/") || path.contains("/video/media/") || path.contains("/audio/media/"));

                    if (isMediaCollection) {
                        mediaUris.add(contentUri);
                    } else {
                        try {
                            if ("file".equalsIgnoreCase(contentUri.getScheme())) {
                                File file = new File(contentUri.getPath());
                                if (!file.delete()) {
                                    errorMessage = "Failed to delete file: " + contentUri.getPath();
                                }
                            } else {
                                myActivity.getContentResolver().delete(contentUri, null, null);
                            }
                        } catch (SecurityException | IllegalArgumentException | NullPointerException e) {
                            errorMessage = e.getMessage();
                        } catch (Exception e) {
                            errorMessage = e.getMessage();
                        }
                    }
                }

                if (!mediaUris.isEmpty()) {
                    try {
                        android.app.PendingIntent pendingIntent = MediaStore.createDeleteRequest(myActivity.getContentResolver(), mediaUris);
                        myActivity.startIntentSenderForResult(
                                pendingIntent.getIntentSender(),
                                GalleryActivity.INTENT_DELETE_FILE,
                                null, 0, 0, 0, null
                        );
                    } catch (IntentSender.SendIntentException | IllegalArgumentException | SecurityException e) {
                        errorMessage = e.getMessage();
                    }
                }
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                for (Uri uri : uris) {
                    Uri contentUri = getContentUri(myActivity, uri);
                    if (contentUri == null) {
                        errorMessage = myActivity.getString(R.string.delete_failed_desc);
                        continue;
                    }
                    try {
                        myActivity.getContentResolver().delete(contentUri, null, null);
                    } catch (RecoverableSecurityException e) {
                        urisToDelete.add(uri);
                        try {
                            myActivity.startIntentSenderForResult(
                                    e.getUserAction().getActionIntent().getIntentSender(),
                                    GalleryActivity.INTENT_DELETE_FILE,
                                    null, 0, 0, 0, null
                            );
                        } catch (IntentSender.SendIntentException ex) {
                            errorMessage = ex.getMessage();
                        }
                    } catch (SecurityException | IllegalArgumentException | NullPointerException e) {
                        errorMessage = e.getMessage();
                    }
                }
            } else {
                for (Uri uri : uris) {
                    Uri contentUri = getContentUri(myActivity, uri);
                    if (contentUri == null) {
                        errorMessage = myActivity.getString(R.string.delete_failed_desc);
                        continue;
                    }
                    try {
                        if ("file".equalsIgnoreCase(contentUri.getScheme())) {
                            File file = new File(contentUri.getPath());
                            if (!file.delete()) {
                                errorMessage = "Failed to delete file: " + contentUri.getPath();
                            }
                        } else {
                            myActivity.getContentResolver().delete(contentUri, null, null);
                        }
                    } catch (SecurityException | IllegalArgumentException | NullPointerException e) {
                        errorMessage = e.getMessage();
                    } catch (Exception e) {
                        errorMessage = e.getMessage();
                    }
                }
            }
        } catch (Exception e) {
            errorMessage = e.getMessage();
        }

        return null;
    }

    private static Long extractMediaIdFromPickerUri(Uri uri) {
        String path = uri.getPath();
        if (path == null) {
            return null;
        }
        String[] segments = path.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            try {
                return Long.parseLong(segments[i]);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    public static Uri getContentUri(final Context context, final Uri uri) {
        if (DocumentsContract.isDocumentUri(context, uri)) {
            if (isExternalStorageDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                if ("primary".equalsIgnoreCase(type)) {
                    return Uri.fromFile(new File(Environment.getExternalStorageDirectory() + "/" + split[1]));
                }
            } else if (isDownloadsDocument(uri)) {
                final String id = DocumentsContract.getDocumentId(uri);
                final Uri contentUri = ContentUris.withAppendedId(
                        Uri.parse("content://downloads/public_downloads"), Long.valueOf(id));

                String dataColumn = getDataColumn(context, contentUri, null, null);
                if (dataColumn == null) {
                    return null;
                }
                return Uri.fromFile(new File(dataColumn));
            } else if (isMediaDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];
                final Long id = Long.parseLong(split[1]);

                Uri contentUri = null;
                if ("image".equals(type)) {
                    contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                } else if ("video".equals(type)) {
                    contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                } else if ("audio".equals(type)) {
                    contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                }

                return contentUri;
            }
        } else if ("content".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath();
            if (path != null && path.contains("picker_get_content")) {
                Long mediaId = extractMediaIdFromPickerUri(uri);
                if (mediaId != null) {
                    String mimeType = context.getContentResolver().getType(uri);
                    if (mimeType != null) {
                        if (mimeType.startsWith("image/")) {
                            return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId);
                        } else if (mimeType.startsWith("video/")) {
                            return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId);
                        } else if (mimeType.startsWith("audio/")) {
                            return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId);
                        }
                    }
                }
                return null;
            }
            return uri;
        } else if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri;
        }

        return null;
    }

    public static String getDataColumn(Context context, Uri uri, String selection, String[] selectionArgs) {
        Cursor cursor = null;
        final String column = "_data";
        final String[] projection = {column};
        try {
            cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                final int column_index = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(column_index);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (cursor != null)
                cursor.close();
        }
        return null;
    }

    public static boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    public static boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    public static boolean isMediaDocument(Uri uri) {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }

    @Override
    protected void onProgressUpdate(Integer... values) {
        super.onProgressUpdate(values);
        if (progress != null) {
            progress.setProgress(values[0]);
        }
    }

    @Override
    protected void onPostExecute(Void result) {
        super.onPostExecute(result);

        if (progress != null && progress.isShowing()) {
            progress.dismiss();
        }

        AppCompatActivity myActivity = activity.get();
        if (myActivity != null) {
            Helpers.releaseWakeLock(myActivity);
        }

        if (onFinish != null) {
            onFinish.onFinish();
        }

        if (errorMessage != null && myActivity != null) {
            Helpers.showAlertDialog(
                    myActivity,
                    myActivity.getString(R.string.delete_failed),
                    errorMessage
            );
        }
    }
}
