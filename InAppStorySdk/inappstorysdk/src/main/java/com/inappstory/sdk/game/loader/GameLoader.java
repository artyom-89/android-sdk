package com.inappstory.sdk.game.loader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.inappstory.sdk.stories.api.models.WebResource;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GameLoader {

    private static final String INDEX_MANE = "/index.html";
    public static final String FILE = "file://";

    private GameLoader() {

    }

    private static volatile GameLoader INSTANCE;


    public static GameLoader getInstance() {
        if (INSTANCE == null) {
            synchronized (GameLoader.class) {
                if (INSTANCE == null)
                    INSTANCE = new GameLoader();
            }
        }
        return INSTANCE;
    }

    private static final ExecutorService gameFileThread = Executors.newFixedThreadPool(1);

    private String getStringFromFile(File fl) throws Exception {
        FileInputStream fin = new FileInputStream(fl);
        String ret = convertStreamToString(fin);
        fin.close();
        return ret;
    }

    private String convertStreamToString(InputStream is) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line = null;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }

    private void deleteFolderRecursive(File fileOrDirectory, boolean deleteRoot) {

        if (fileOrDirectory.isDirectory()) {
            for (File child : fileOrDirectory.listFiles()) {
                deleteFolderRecursive(child, true);
            }
        }
        if (deleteRoot) {
            try {
                fileOrDirectory.delete();
            } catch (Exception e) {

            }
        }
    }

    private void downloadResources(final List<WebResource> resources,
                                          final File file,
                                          final GameLoadCallback callback,
                                          final int totalSize,
                                          final int curSize) {
        if (terminate) return;
        String pathName = file.getAbsolutePath();
        final File filePath = new File(pathName + "/src/");
        if (!filePath.exists()) {
            filePath.mkdirs();
        }
        gameFileThread.submit(new Callable<Void>() {
            @Override
            public Void call() {
                int cnt = curSize;
                for (WebResource resource : resources) {
                    if (terminate) return null;
                    try {
                        String url = resource.url;
                        String fileName = resource.key;
                        if (url == null || url.isEmpty() || fileName == null || fileName.isEmpty())
                            continue;
                        URL uri = new URL(url);
                        File file = new File(filePath.getAbsolutePath() + "/" + fileName);
                        if (file.exists()) {
                            cnt += resource.size;
                            if (callback != null)
                                callback.onProgress(cnt, totalSize);
                            continue;
                        }
                        URLConnection connection = uri.openConnection();
                        connection.connect();
                        cnt = downloadStream(uri, file, callback, cnt, totalSize);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            File fl = new File(file.getAbsolutePath() + INDEX_MANE);
                            try {
                                callback.onLoad(FILE + fl.getAbsolutePath(), getStringFromFile(fl));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    });
                }
                return null;
            }
        });

    }

    public void downloadAndUnzip(final Context context,
                                        final List<WebResource> resources,
                                        final String url,
                                        final String pathName,
                                        final GameLoadCallback callback) {
        terminate = false;
        gameFileThread.submit(new Callable() {
            @Override
            public Void call() {
                try {
                    URL uri = new URL(url);
                    File file = new File(context.getFilesDir() + "/zip/" + pathName + "/" + url.hashCode() + ".zip");

                    //TODO remove after tests
               /* if (file.exists()) {
                    File parentFolder = file.getParentFile();
                    if (parentFolder != null && parentFolder.exists()) {
                        deleteFolderRecursive(parentFolder, true);
                    }
                }*/
                    int curSize = 0;
                    int totalSize = 0;
                    for (WebResource resource : resources) {
                        totalSize += resource.size;
                    }
                    if (!file.exists()) {
                        try {
                            file.mkdirs();
                        } catch (Exception e) {

                        }
                        if (file.exists()) file.delete();
                        File parentFolder = file.getParentFile();
                        if (parentFolder != null && parentFolder.exists()) {
                            deleteFolderRecursive(parentFolder, false);
                        }
                        URLConnection connection = uri.openConnection();
                        connection.connect();
                        int sz = connection.getContentLength();
                        curSize = sz;
                        totalSize += sz;
                        downloadStream(uri, file, callback, 0, totalSize);
                    }
                    File directory = new File(file.getParent() + "/" + url.hashCode());
                    if (directory.exists())
                        downloadResources(resources, directory, callback, totalSize, curSize);
                    else if (file.exists()) {
                        FileUnzipper.unzip(file, directory);
                        downloadResources(resources, directory, callback, totalSize, curSize);
                    } else {
                        if (callback != null)
                            callback.onError();
                    }
                } catch (Exception e) {
                    if (callback != null)
                        callback.onError();
                }
                return null;
            }
        });
    }

    boolean terminate = false;

    public void terminate() {
        terminate = true;
    }

    private int downloadStream(URL uri, File file, GameLoadCallback callback, int startSize, int totalSize) {
        try {
            int count;
            InputStream input = new BufferedInputStream(uri.openStream(),
                    8192);
            OutputStream output = new FileOutputStream(file);
            byte data[] = new byte[1024];
            int cnt = startSize;
            while ((count = input.read(data)) != -1) {
                output.write(data, 0, count);
                cnt += count;
                callback.onProgress(cnt, totalSize);
            }
            output.flush();
            output.close();
            input.close();
            return cnt;
        } catch (Exception e) {
            if (file.exists())
                file.delete();
            return startSize;
        }
    }
}
