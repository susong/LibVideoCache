package com.dream.video.cache;

import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PARTIAL;
import static java.net.HttpURLConnection.HTTP_SEE_OTHER;
import static com.dream.video.cache.Preconditions.checkNotNull;
import static com.dream.video.cache.ProxyCacheUtils.DEFAULT_BUFFER_SIZE;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.LogUtils;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import com.dream.video.cache.headers.EmptyHeadersInjector;
import com.dream.video.cache.headers.HeaderInjector;
import com.dream.video.cache.sourcestorage.SourceInfoStorage;
import com.dream.video.cache.sourcestorage.SourceInfoStorageFactory;
import okhttp3.Call;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * {@link Source} that uses http resource as source for {@link ProxyCache}.
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
public class HttpUrlSource implements Source {

    private static final int MAX_REDIRECTS = 5;
    private final SourceInfoStorage sourceInfoStorage;
    private final HeaderInjector headerInjector;
    private SourceInfo sourceInfo;
    private InputStream inputStream;

    private static final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(10, TimeUnit.SECONDS) // 连接超时
            .readTimeout(10, TimeUnit.SECONDS) // 读取超时
            .writeTimeout(10, TimeUnit.SECONDS) //  写超时
            .dns(new XDns(10, TimeUnit.SECONDS)) // DNS超时
            .build();
    private Call requestCall = null;

    public HttpUrlSource(String url) {
        this(url, SourceInfoStorageFactory.newEmptySourceInfoStorage());
    }

    public HttpUrlSource(String url, SourceInfoStorage sourceInfoStorage) {
        this(url, sourceInfoStorage, new EmptyHeadersInjector());
    }

    public HttpUrlSource(String url, SourceInfoStorage sourceInfoStorage, HeaderInjector headerInjector) {
        this.sourceInfoStorage = checkNotNull(sourceInfoStorage);
        this.headerInjector = checkNotNull(headerInjector);
        SourceInfo sourceInfo = sourceInfoStorage.get(url);
        this.sourceInfo = sourceInfo != null ? sourceInfo :
                new SourceInfo(url, Integer.MIN_VALUE, ProxyCacheUtils.getSupposablyMime(url));
    }

    public HttpUrlSource(HttpUrlSource source) {
        this.sourceInfo = source.sourceInfo;
        this.sourceInfoStorage = source.sourceInfoStorage;
        this.headerInjector = source.headerInjector;
    }

    @Override
    public void open(long offset) throws ProxyCacheException {
        try {
            Response response = openConnection(offset);
            String mime = response.header("Content-Type");
            inputStream = new BufferedInputStream(response.body().byteStream(), DEFAULT_BUFFER_SIZE);
            long length = readSourceAvailableBytes(response, offset, response.code());
            this.sourceInfo = new SourceInfo(sourceInfo.url, length, mime);
            this.sourceInfoStorage.put(sourceInfo.url, sourceInfo);
        } catch (IOException e) {
            throw new ProxyCacheException("Error opening connection for " + sourceInfo.url + " with offset " + offset, e);
        }
    }

    private long readSourceAvailableBytes(Response response, long offset, int responseCode) throws IOException {
        long contentLength = Long.valueOf(response.header("Content-Length", "-1"));
        return responseCode == HTTP_OK ? contentLength
                : responseCode == HTTP_PARTIAL ? contentLength + offset : sourceInfo.length;
    }

    @Override
    public long length() throws ProxyCacheException {
        if (sourceInfo.length == Integer.MIN_VALUE) {
            fetchContentInfo();
        }
        return sourceInfo.length;
    }

    @Override
    public int read(byte[] buffer) throws ProxyCacheException {
        if (inputStream == null) {
            throw new ProxyCacheException("Error reading data from " + sourceInfo.url + ": connection is absent!");
        }
        try {
            return inputStream.read(buffer, 0, buffer.length);
        } catch (InterruptedIOException e) {
            throw new InterruptedProxyCacheException("Reading source " + sourceInfo.url + " is interrupted", e);
        } catch (IOException e) {
            throw new ProxyCacheException("Error reading data from " + sourceInfo.url, e);
        }
    }

    @Override
    public void close() throws ProxyCacheException {
        if (okHttpClient != null && inputStream != null && requestCall != null) {
            try {
                inputStream.close();
                requestCall.cancel();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void fetchContentInfo() throws ProxyCacheException {
        LogUtils.iTag(HttpUrlSource.class.getName(), "Read content info from  %s", sourceInfo.url);
        Response response = null;
        InputStream inputStream = null;
        try {
            response = openConnectionForHeader();
            long length = Long.valueOf(response.header("Content-Length", "-1"));
            String mime = response.header("Content-Type");
            inputStream = response.body().byteStream();
            this.sourceInfo = new SourceInfo(sourceInfo.url, length, mime);
            this.sourceInfoStorage.put(sourceInfo.url, sourceInfo);
            LogUtils.iTag(HttpUrlSource.class.getName(), "Source info fetched: " + sourceInfo);
        } catch (IOException e) {
            LogUtils.eTag(HttpUrlSource.class.getName(), "Error fetching info from %s,%s", sourceInfo.url, e.getMessage());
        } finally {
            ProxyCacheUtils.close(inputStream);
            if (response != null) {
                requestCall.cancel();
            }
        }
    }

    // for HEAD
    private Response openConnectionForHeader() throws IOException, ProxyCacheException {
        Response response;
        boolean isRedirect = false;
        String newUrl = this.sourceInfo.url;
        int redirectCount = 0;
        do {
            //只返回头部，不需要BODY，既可以提高响应速度也可以减少网络流量
            Request request = new Request.Builder()
                    .head()
                    .url(newUrl)
                    .build();
            requestCall = okHttpClient.newCall(request);
            response = requestCall.execute();
            if (response.isRedirect()) {
                newUrl = response.header("Location");
                LogUtils.iTag(HttpUrlSource.class.getName(), "Redirect to: %s", newUrl);
                isRedirect = response.isRedirect();
                redirectCount++;
                requestCall.cancel();
                LogUtils.iTag(HttpUrlSource.class.getName(), "Redirect to: %s", newUrl);
            }
            if (redirectCount > MAX_REDIRECTS) {
                throw new ProxyCacheException("Too many redirects: " + redirectCount);
            }
        } while (isRedirect);

        return response;
    }

    private long getContentLength(HttpURLConnection connection) {
        String contentLengthValue = connection.getHeaderField("Content-Length");
        return contentLengthValue == null ? -1 : Long.parseLong(contentLengthValue);
    }

    private Response openConnection(long offset) throws IOException, ProxyCacheException {
        Response response;
        boolean redirected;
        int redirectCount = 0;
        String url = this.sourceInfo.url;
        do {
            Request request = null;
            Request.Builder builder = new Request.Builder();
            builder.get();
            builder.url(url);
            injectCustomHeaders(builder, url);
            if (offset > 0) {
                builder.addHeader("Range", "bytes=" + offset + "-");
            }
            request = builder.build();
            requestCall = okHttpClient.newCall(request);
            response = requestCall.execute();
            int code = response.code();
            redirected = code == HTTP_MOVED_PERM || code == HTTP_MOVED_TEMP || code == HTTP_SEE_OTHER;
            if (redirected) {
                url = response.header("Location");
                redirectCount++;
                requestCall.cancel();
            }
            if (redirectCount > MAX_REDIRECTS) {
                throw new ProxyCacheException("Too many redirects: " + redirectCount);
            }
        } while (redirected);

        return response;
    }

    private void injectCustomHeaders(Request.Builder builder, String url) {
        Map<String, String> extraHeaders = headerInjector.addHeaders(url);
        for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
            builder.addHeader(header.getKey(), header.getValue());
        }
    }

    public synchronized String getMime() throws ProxyCacheException {
        if (TextUtils.isEmpty(sourceInfo.mime)) {
            fetchContentInfo();
        }
        return sourceInfo.mime;
    }

    public String getUrl() {
        return sourceInfo.url;
    }

    public static class XDns implements Dns {
        private final long timeout;
        private final TimeUnit unit;

        public XDns(long timeout, TimeUnit unit) {
            this.timeout = timeout;
            this.unit = unit;
        }

        @NonNull
        @Override
        public List<InetAddress> lookup(@NonNull final String hostname) throws UnknownHostException {
            try {
                FutureTask<List<InetAddress>> task = new FutureTask<>(() ->
                        Arrays.asList(InetAddress.getAllByName(hostname)));
                new Thread(task).start();
                return task.get(timeout, unit);
            } catch (Exception e) {
                UnknownHostException unknownHostException =
                        new UnknownHostException("Broken system behaviour for dns lookup of " + hostname);
                unknownHostException.initCause(e);
                throw unknownHostException;
            }
        }
    }
}
