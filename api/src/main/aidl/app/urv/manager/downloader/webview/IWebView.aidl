// IWebView.aidl
package app.urv.manager.downloader.webview;

@JavaPassthrough(annotation="@app.urv.manager.downloader.DownloaderHostApi")
oneway interface IWebView {
    void load(String url);
    void finish();
}
