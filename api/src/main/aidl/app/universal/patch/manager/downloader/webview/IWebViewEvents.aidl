// IWebViewEvents.aidl
package app.universal.patch.manager.downloader.webview;

import app.universal.patch.manager.downloader.webview.IWebView;

@JavaPassthrough(annotation="@app.universal.patch.manager.downloader.DownloaderHostApi")
oneway interface IWebViewEvents {
    void ready(IWebView iface);
    void pageLoad(String url);
    void download(String url, String mimetype, String userAgent);
}
