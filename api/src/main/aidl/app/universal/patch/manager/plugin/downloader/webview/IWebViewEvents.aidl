// IWebViewEvents.aidl
package app.universal.patch.manager.plugin.downloader.webview;

import app.universal.patch.manager.plugin.downloader.webview.IWebView;

@JavaPassthrough(annotation="@app.universal.patch.manager.plugin.downloader.PluginHostApi")
oneway interface IWebViewEvents {
    void ready(IWebView iface);
    void pageLoad(String url);
    void download(String url, String mimetype, String userAgent);
}