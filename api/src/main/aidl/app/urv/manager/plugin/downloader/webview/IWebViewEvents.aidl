// IWebViewEvents.aidl
package app.urv.manager.plugin.downloader.webview;

import app.urv.manager.plugin.downloader.webview.IWebView;

@JavaPassthrough(annotation="@app.urv.manager.plugin.downloader.PluginHostApi")
oneway interface IWebViewEvents {
    void ready(IWebView iface);
    void pageLoad(String url);
    void download(String url, String mimetype, String userAgent);
}