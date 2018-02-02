package com.microsoft.codepush.react.managers;

import android.os.AsyncTask;

import com.microsoft.codepush.react.CodePushConstants;
import com.microsoft.codepush.react.CodePushCore;
import com.microsoft.codepush.react.CodePushDownloadPackageResult;
import com.microsoft.codepush.react.exceptions.CodePushInvalidUpdateException;
import com.microsoft.codepush.react.exceptions.CodePushMalformedDataException;
import com.microsoft.codepush.react.exceptions.CodePushUnknownException;
import com.microsoft.codepush.react.utils.CodePushRNUtils;
import com.microsoft.codepush.react.utils.CodePushUpdateUtils;
import com.microsoft.codepush.react.utils.CodePushUtils;
import com.microsoft.codepush.react.DownloadProgress;
import com.microsoft.codepush.react.utils.FileUtils;
import com.microsoft.codepush.react.interfaces.DownloadProgressCallback;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;

public class CodePushUpdateManager {

    private String mDocumentsDirectory;

    public CodePushUpdateManager(String documentsDirectory) {
        mDocumentsDirectory = documentsDirectory;
    }

    private String getDownloadFilePath() {
        return FileUtils.appendPathComponent(getCodePushPath(), CodePushConstants.DOWNLOAD_FILE_NAME);
    }

    private String getUnzippedFolderPath() {
        return FileUtils.appendPathComponent(getCodePushPath(), CodePushConstants.UNZIPPED_FOLDER_NAME);
    }

    private String getDocumentsDirectory() {
        return mDocumentsDirectory;
    }

    private String getCodePushPath() {
        String codePushPath = FileUtils.appendPathComponent(getDocumentsDirectory(), CodePushConstants.CODE_PUSH_FOLDER_PREFIX);
        if (CodePushCore.isUsingTestConfiguration()) {
            codePushPath = FileUtils.appendPathComponent(codePushPath, "TestPackages");
        }

        return codePushPath;
    }

    private String getStatusFilePath() {
        return FileUtils.appendPathComponent(getCodePushPath(), CodePushConstants.STATUS_FILE);
    }

    public JSONObject getCurrentPackageInfo() {
        String statusFilePath = getStatusFilePath();
        if (!FileUtils.fileAtPathExists(statusFilePath)) {
            return new JSONObject();
        }

        try {
            return CodePushUtils.getJsonObjectFromFile(statusFilePath);
        } catch (IOException e) {
            // Should not happen.
            throw new CodePushUnknownException("Error getting current package info", e);
        }
    }

    public void updateCurrentPackageInfo(JSONObject packageInfo) {
        try {
            CodePushUtils.writeJsonToFile(packageInfo, getStatusFilePath());
        } catch (IOException e) {
            // Should not happen.
            throw new CodePushUnknownException("Error updating current package info", e);
        }
    }

    public String getCurrentPackageFolderPath() {
        JSONObject info = getCurrentPackageInfo();
        String packageHash = info.optString(CodePushConstants.CURRENT_PACKAGE_KEY, null);
        if (packageHash == null) {
            return null;
        }

        return getPackageFolderPath(packageHash);
    }

    public String getCurrentPackageBundlePath(String bundleFileName) {
        String packageFolder = getCurrentPackageFolderPath();
        if (packageFolder == null) {
            return null;
        }

        JSONObject currentPackage = getCurrentPackage();
        if (currentPackage == null) {
            return null;
        }

        String relativeBundlePath = currentPackage.optString(CodePushConstants.RELATIVE_BUNDLE_PATH_KEY, null);
        if (relativeBundlePath == null) {
            return FileUtils.appendPathComponent(packageFolder, bundleFileName);
        } else {
            return FileUtils.appendPathComponent(packageFolder, relativeBundlePath);
        }
    }

    public String getPackageFolderPath(String packageHash) {
        return FileUtils.appendPathComponent(getCodePushPath(), packageHash);
    }

    public String getCurrentPackageHash() {
        JSONObject info = getCurrentPackageInfo();
        return info.optString(CodePushConstants.CURRENT_PACKAGE_KEY, null);
    }

    public String getPreviousPackageHash() {
        JSONObject info = getCurrentPackageInfo();
        return info.optString(CodePushConstants.PREVIOUS_PACKAGE_KEY, null);
    }

    public JSONObject getCurrentPackage() {
        String packageHash = getCurrentPackageHash();
        if (packageHash == null) {
            return null;
        }

        return getPackage(packageHash);
    }

    public JSONObject getPreviousPackage() {
        String packageHash = getPreviousPackageHash();
        if (packageHash == null) {
            return null;
        }

        return getPackage(packageHash);
    }

    public JSONObject getPackage(String packageHash) {
        String folderPath = getPackageFolderPath(packageHash);
        String packageFilePath = FileUtils.appendPathComponent(folderPath, CodePushConstants.PACKAGE_FILE_NAME);
        try {
            return CodePushUtils.getJsonObjectFromFile(packageFilePath);
        } catch (IOException e) {
            return null;
        }
    }

    public void downloadPackage(JSONObject updatePackage, String expectedBundleFileName,
                                final DownloadProgressCallback progressCallback, String stringPublicKey) throws IOException {
        String newUpdateHash = updatePackage.optString(CodePushConstants.PACKAGE_HASH_KEY, null);
        String newUpdateFolderPath = getPackageFolderPath(newUpdateHash);
        String newUpdateMetadataPath = FileUtils.appendPathComponent(newUpdateFolderPath, CodePushConstants.PACKAGE_FILE_NAME);
        if (FileUtils.fileAtPathExists(newUpdateFolderPath)) {
            // This removes any stale data in newPackageFolderPath that could have been left
            // uncleared due to a crash or error during the download or install process.
            FileUtils.deleteDirectoryAtPath(newUpdateFolderPath);
        }

        final String downloadUrlString = updatePackage.optString(CodePushConstants.DOWNLOAD_URL_KEY, null);

        // Download the file while checking if it is a zip and notifying client of progress.
        AsyncTask<Void, Void, CodePushDownloadPackageResult> downloadTask = new AsyncTask<Void, Void, CodePushDownloadPackageResult>() {
            @Override
            protected CodePushDownloadPackageResult doInBackground(Void... params) {
                HttpURLConnection connection = null;
                BufferedInputStream bin = null;
                FileOutputStream fos = null;
                BufferedOutputStream bout = null;

                try {
                    URL downloadUrl = new URL(downloadUrlString);

                    connection = (HttpURLConnection) (downloadUrl.openConnection());

                    long totalBytes = connection.getContentLength();
                    long receivedBytes = 0;

                    bin = new BufferedInputStream(connection.getInputStream());
                    File downloadFolder = new File(getCodePushPath());
                    downloadFolder.mkdirs();
                    File downloadFile = new File(downloadFolder, CodePushConstants.DOWNLOAD_FILE_NAME);
                    fos = new FileOutputStream(downloadFile);
                    bout = new BufferedOutputStream(fos, CodePushConstants.DOWNLOAD_BUFFER_SIZE);
                    byte[] data = new byte[CodePushConstants.DOWNLOAD_BUFFER_SIZE];
                    byte[] header = new byte[4];

                    int numBytesRead = 0;
                    while ((numBytesRead = bin.read(data, 0, CodePushConstants.DOWNLOAD_BUFFER_SIZE)) >= 0) {
                        if (receivedBytes < 4) {
                            for (int i = 0; i < numBytesRead; i++) {
                                int headerOffset = (int) (receivedBytes) + i;
                                if (headerOffset >= 4) {
                                    break;
                                }

                                header[headerOffset] = data[i];
                            }
                        }

                        receivedBytes += numBytesRead;
                        bout.write(data, 0, numBytesRead);
                        progressCallback.call(new DownloadProgress(totalBytes, receivedBytes));
                    }

                    if (totalBytes != receivedBytes) {
                        throw new CodePushUnknownException("Received " + receivedBytes + " bytes, expected " + totalBytes);
                    }

                    boolean isZip = ByteBuffer.wrap(header).getInt() == 0x504b0304;

                    return new CodePushDownloadPackageResult(downloadFile, isZip);
                } catch (MalformedURLException e) {
                    throw new CodePushMalformedDataException(downloadUrlString, e);
                } catch (Exception e) {
                    throw new CodePushUnknownException("Error occured while downloading package.", e);
                } finally {
                    try {
                        if (bout != null) bout.close();
                        if (fos != null) fos.close();
                        if (bin != null) bin.close();
                        if (connection != null) connection.disconnect();
                    } catch (IOException e) {
                        throw new CodePushUnknownException("Error closing IO resources.", e);
                    }
                }
            }
        };

        downloadTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

        CodePushDownloadPackageResult downloadPackageResult = null;
        try {
            downloadPackageResult = downloadTask.get();
        } catch (InterruptedException e) {
            throw new CodePushUnknownException("Error occured while downloading package.", e);
        } catch (ExecutionException e) {
            throw new CodePushUnknownException("Error occured while downloading package.", e);
        }

        File downloadFile = downloadPackageResult.getDownloadFile();
        boolean isZip = downloadPackageResult.isZip();

        if (isZip) {
            // Unzip the downloaded file and then delete the zip
            String unzippedFolderPath = getUnzippedFolderPath();
            FileUtils.unzipFile(downloadFile, unzippedFolderPath);
            FileUtils.deleteFileOrFolderSilently(downloadFile);

            // Merge contents with current update based on the manifest
            String diffManifestFilePath = FileUtils.appendPathComponent(unzippedFolderPath,
                    CodePushConstants.DIFF_MANIFEST_FILE_NAME);
            boolean isDiffUpdate = FileUtils.fileAtPathExists(diffManifestFilePath);
            if (isDiffUpdate) {
                String currentPackageFolderPath = getCurrentPackageFolderPath();
                CodePushUpdateUtils.copyNecessaryFilesFromCurrentPackage(diffManifestFilePath, currentPackageFolderPath, newUpdateFolderPath);
                File diffManifestFile = new File(diffManifestFilePath);
                diffManifestFile.delete();
            }

            FileUtils.copyDirectoryContents(unzippedFolderPath, newUpdateFolderPath);
            FileUtils.deleteFileAtPathSilently(unzippedFolderPath);

            // For zip updates, we need to find the relative path to the jsBundle and save it in the
            // metadata so that we can find and run it easily the next time.
            String relativeBundlePath = CodePushRNUtils.findJSBundleInUpdateContents(newUpdateFolderPath, expectedBundleFileName);

            if (relativeBundlePath == null) {
                throw new CodePushInvalidUpdateException("Update is invalid - A JS bundle file named \"" + expectedBundleFileName + "\" could not be found within the downloaded contents. Please check that you are releasing your CodePush updates using the exact same JS bundle file name that was shipped with your app's binary.");
            } else {
                if (FileUtils.fileAtPathExists(newUpdateMetadataPath)) {
                    File metadataFileFromOldUpdate = new File(newUpdateMetadataPath);
                    metadataFileFromOldUpdate.delete();
                }

                if (isDiffUpdate) {
                    CodePushRNUtils.log("Applying diff update.");
                } else {
                    CodePushRNUtils.log("Applying full update.");
                }

                boolean isSignatureVerificationEnabled = (stringPublicKey != null);

                String signaturePath = CodePushUpdateUtils.getJWTFilePath(newUpdateFolderPath);
                boolean isSignatureAppearedInBundle = FileUtils.fileAtPathExists(signaturePath);

                if (isSignatureVerificationEnabled) {
                    if (isSignatureAppearedInBundle) {
                        CodePushUpdateUtils.verifyFolderHash(newUpdateFolderPath, newUpdateHash);
                        CodePushUpdateUtils.verifyUpdateSignature(newUpdateFolderPath, newUpdateHash, stringPublicKey);
                    } else {
                        throw new CodePushInvalidUpdateException(
                                "Error! Public key was provided but there is no JWT signature within app bundle to verify. " +
                                        "Possible reasons, why that might happen: \n" +
                                        "1. You've been released CodePush bundle update using version of CodePush CLI that is not support code signing.\n" +
                                        "2. You've been released CodePush bundle update without providing --privateKeyPath option."
                        );
                    }
                } else {
                    if (isSignatureAppearedInBundle) {
                        CodePushRNUtils.log(
                                "Warning! JWT signature exists in codepush update but code integrity check couldn't be performed because there is no public key configured. " +
                                        "Please ensure that public key is properly configured within your application."
                        );
                        CodePushUpdateUtils.verifyFolderHash(newUpdateFolderPath, newUpdateHash);
                    } else {
                        if (isDiffUpdate) {
                            CodePushUpdateUtils.verifyFolderHash(newUpdateFolderPath, newUpdateHash);
                        }
                    }
                }

                CodePushUtils.setJSONValueForKey(updatePackage, CodePushConstants.RELATIVE_BUNDLE_PATH_KEY, relativeBundlePath);
            }
        } else {
            // File is a jsbundle, move it to a folder with the packageHash as its name
            FileUtils.moveFile(downloadFile, newUpdateFolderPath, expectedBundleFileName);
        }

        // Save metadata to the folder.
        CodePushUtils.writeJsonToFile(updatePackage, newUpdateMetadataPath);
    }

    public void installPackage(JSONObject updatePackage, boolean removePendingUpdate) {
        String packageHash = updatePackage.optString(CodePushConstants.PACKAGE_HASH_KEY, null);
        JSONObject info = getCurrentPackageInfo();

        String currentPackageHash = info.optString(CodePushConstants.CURRENT_PACKAGE_KEY, null);
        if (packageHash != null && packageHash.equals(currentPackageHash)) {
            // The current package is already the one being installed, so we should no-op.
            return;
        }

        if (removePendingUpdate) {
            String currentPackageFolderPath = getCurrentPackageFolderPath();
            if (currentPackageFolderPath != null) {
                FileUtils.deleteDirectoryAtPath(currentPackageFolderPath);
            }
        } else {
            String previousPackageHash = getPreviousPackageHash();
            if (previousPackageHash != null && !previousPackageHash.equals(packageHash)) {
                FileUtils.deleteDirectoryAtPath(getPackageFolderPath(previousPackageHash));
            }

            CodePushUtils.setJSONValueForKey(info, CodePushConstants.PREVIOUS_PACKAGE_KEY, info.optString(CodePushConstants.CURRENT_PACKAGE_KEY, null));
        }

        CodePushUtils.setJSONValueForKey(info, CodePushConstants.CURRENT_PACKAGE_KEY, packageHash);
        updateCurrentPackageInfo(info);
    }

    public void rollbackPackage() {
        JSONObject info = getCurrentPackageInfo();
        String currentPackageFolderPath = getCurrentPackageFolderPath();
        FileUtils.deleteDirectoryAtPath(currentPackageFolderPath);
        CodePushUtils.setJSONValueForKey(info, CodePushConstants.CURRENT_PACKAGE_KEY, info.optString(CodePushConstants.PREVIOUS_PACKAGE_KEY, null));
        CodePushUtils.setJSONValueForKey(info, CodePushConstants.PREVIOUS_PACKAGE_KEY, null);
        updateCurrentPackageInfo(info);
    }

    public void downloadAndReplaceCurrentBundle(final String remoteBundleUrl, final String bundleFileName) throws IOException {

        AsyncTask<Void, Void, Void> asyncTask = new AsyncTask<Void, Void, Void>() {
            URL downloadUrl;
            HttpURLConnection connection = null;
            BufferedInputStream bin = null;
            FileOutputStream fos = null;
            BufferedOutputStream bout = null;
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    downloadUrl = new URL(remoteBundleUrl);
                    connection = (HttpURLConnection) (downloadUrl.openConnection());
                    bin = new BufferedInputStream(connection.getInputStream());
                    File downloadFile = new File(getCurrentPackageBundlePath(bundleFileName));
                    downloadFile.delete();
                    fos = new FileOutputStream(downloadFile);
                    bout = new BufferedOutputStream(fos, CodePushConstants.DOWNLOAD_BUFFER_SIZE);
                    byte[] data = new byte[CodePushConstants.DOWNLOAD_BUFFER_SIZE];
                    int numBytesRead = 0;
                    while ((numBytesRead = bin.read(data, 0, CodePushConstants.DOWNLOAD_BUFFER_SIZE)) >= 0) {
                        bout.write(data, 0, numBytesRead);
                    }
                } catch (MalformedURLException e) {
                    throw new CodePushMalformedDataException(remoteBundleUrl, e);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    try {
                        if (bout != null) bout.close();
                        if (fos != null) fos.close();
                        if (bin != null) bin.close();
                        if (connection != null) connection.disconnect();
                    } catch (IOException e) {
                        throw new CodePushUnknownException("Error closing IO resources.", e);
                    }
                }
                return null;
            }
        };

        asyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

        try {
            asyncTask.get();
        } catch (InterruptedException e) {
            throw new CodePushUnknownException("Error occured while downloading package.", e);
        } catch (ExecutionException e) {
            throw new CodePushUnknownException("Error occured while downloading package.", e);
        }
    }

    public void clearUpdates() {
        FileUtils.deleteDirectoryAtPath(getCodePushPath());
    }
}