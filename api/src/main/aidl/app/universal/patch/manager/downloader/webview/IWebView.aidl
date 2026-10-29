// IWebView.aidl
package app.universal.patch.manager.downloader.webview;

@JavaPassthrough(annotation="@app.universal.patch.manager.downloader.DownloaderHostApi")
oneway interface IWebView {
    void load(String url);
    void finish();
}
