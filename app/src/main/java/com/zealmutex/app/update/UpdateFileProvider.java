package com.zealmutex.app.update;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Read-only provider exposing only the verified update APK to the system installer. */
public final class UpdateFileProvider extends ContentProvider {
    private static final String UPDATE_PATH = "/update.apk";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        requireUpdateUri(uri);
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        requireUpdateUri(uri);
        File apk = updateApk();
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                row.add("ZealMutex-update.apk");
            } else if (OpenableColumns.SIZE.equals(column)) {
                row.add(apk.length());
            } else {
                row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        requireUpdateUri(uri);
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Update APK is read-only");
        }
        File apk = updateApk();
        if (!apk.isFile()) {
            throw new FileNotFoundException("Update APK is unavailable");
        }
        return ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    private void requireUpdateUri(Uri uri) {
        if (uri == null || !UPDATE_PATH.equals(uri.getPath())) {
            throw new IllegalArgumentException("Unknown update path");
        }
    }

    private File updateApk() {
        if (getContext() == null) {
            return new File("");
        }
        return UpdateManager.updateApk(getContext());
    }
}
