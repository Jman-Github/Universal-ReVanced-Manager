// IWebView.aidl
package app.urv.manager.plugin.downloader.webview;

@JavaPassthrough(annotation="@app.urv.manager.plugin.downloader.PluginHostApi")
oneway interface IWebView {
    void load(String url);
    void finish();
}