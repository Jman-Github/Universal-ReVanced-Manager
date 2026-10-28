// IWebView.aidl
package app.universal.patch.manager.plugin.downloader.webview;

@JavaPassthrough(annotation="@app.universal.patch.manager.plugin.downloader.PluginHostApi")
oneway interface IWebView {
    void load(String url);
    void finish();
}