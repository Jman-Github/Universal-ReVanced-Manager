// IWebViewEvents.aidl
package app.urv.manager.downloader.webview;

import app.urv.manager.downloader.webview.IWebView;

@JavaPassthrough(annotation="@app.urv.manager.downloader.DownloaderHostApi")
oneway interface IWebViewEvents {
    void ready(IWebView iface);
    void pageLoad(String url);
    void download(String url, String mimetype, String userAgent);
}
