package piuk.blockchain.android.ui.buy;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.databinding.DataBindingUtil;
import android.net.MailTo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.StringRes;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.AppCompatEditText;
import android.text.InputType;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.crashlytics.android.answers.PurchaseEvent;
import com.facebook.device.yearclass.YearClass;

import javax.inject.Inject;

import piuk.blockchain.android.BuildConfig;
import piuk.blockchain.android.R;
import piuk.blockchain.android.data.answers.Logging;
import piuk.blockchain.android.data.exchange.models.WebViewLoginDetails;
import piuk.blockchain.android.databinding.ActivityBuyBinding;
import piuk.blockchain.android.injection.Injector;
import piuk.blockchain.android.ui.balance.BalanceFragment;
import piuk.blockchain.android.ui.base.BaseMvpActivity;
import piuk.blockchain.android.ui.base.UiState;
import piuk.blockchain.android.ui.customviews.MaterialProgressDialog;
import piuk.blockchain.android.ui.customviews.ToastCustom;
import piuk.blockchain.android.ui.home.MainActivity;
import piuk.blockchain.android.ui.transactions.TransactionDetailActivity;
import piuk.blockchain.android.util.AndroidUtils;
import piuk.blockchain.android.util.PermissionUtil;
import piuk.blockchain.android.util.ViewUtils;
import piuk.blockchain.android.util.annotations.Thunk;
import timber.log.Timber;

import static piuk.blockchain.android.ui.base.UiState.CONTENT;
import static piuk.blockchain.android.ui.base.UiState.EMPTY;
import static piuk.blockchain.android.ui.base.UiState.FAILURE;
import static piuk.blockchain.android.ui.base.UiState.LOADING;

public class BuyActivity extends BaseMvpActivity<BuyView, BuyPresenter>
        implements BuyView, FrontendJavascript<String> {

    @Inject BuyPresenter buyPresenter;

    private FrontendJavascriptManager frontendJavascriptManager;
    private WebViewLoginDetails webViewLoginDetails;
    private MaterialProgressDialog progress;
    private ActivityBuyBinding binding;
    @Thunk PermissionRequest permissionRequest;

    private boolean frontendInitialized = false;
    private boolean didBuyBitcoin = false;

    {
        Injector.getInstance().getPresenterComponent().inject(this);
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, BuyActivity.class);
        context.startActivity(intent);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_buy);

        setupToolbar(binding.toolbarContainer.toolbarGeneral, "");

        if (AndroidUtils.is21orHigher()) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webview, true);
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        }

        frontendJavascriptManager = new FrontendJavascriptManager(this, binding.webview);

        binding.webview.setWebViewClient(new EmailAwareWebViewClient());
        binding.webview.setWebChromeClient(new WebChromeClient() {
            @TargetApi(Build.VERSION_CODES.LOLLIPOP)
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                permissionRequest = request;
                requestScanPermissions();
            }
        });
        binding.webview.addJavascriptInterface(frontendJavascriptManager, FrontendJavascriptManager.JS_INTERFACE_NAME);
        binding.webview.getSettings().setJavaScriptEnabled(true);
        binding.webview.loadUrl(getPresenter().getCurrentServerUrl());
        onViewReady();
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void grantPermissionToWebView() {
        permissionRequest.grant(permissionRequest.getResources());
    }

    @Thunk
    void requestScanPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            PermissionUtil.requestCameraPermissionFromActivity(binding.getRoot(), this);
        } else {
            grantPermissionToWebView();
        }
    }

    @SuppressWarnings("StatementWithEmptyBody")
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PermissionUtil.PERMISSION_REQUEST_CAMERA) {
            if (grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                grantPermissionToWebView();
            } else {
                // Permission request was denied.
            }
        }
    }

    @Override
    public boolean shouldShowRequestPermissionRationale(@NonNull String permission) {
        return true;
    }

    @Override
    protected void onDestroy() {
        binding.webview.removeJavascriptInterface(FrontendJavascriptManager.JS_INTERFACE_NAME);
        binding.webview.reload();

        if (didBuyBitcoin) {
            getPresenter().reloadExchangeDate();
        }
        dismissProgressDialog();
        // Presenter nulled out here
        super.onDestroy();
    }

    @Override
    public void onReceiveValue(String value) {
        Timber.d("Received JS value: %s", value);
    }

    public void setWebViewLoginDetails(WebViewLoginDetails webViewLoginDetails) {
        Timber.d("setWebViewLoginDetails: done");
        this.webViewLoginDetails = webViewLoginDetails;
        activateIfReady();
    }

    @Override
    public void onFrontendInitialized() {
        Timber.d("onFrontendInitialized: done");
        frontendInitialized = true;
        activateIfReady();
    }

    @Override
    public void onBuyCompleted() {
        Timber.d("onBuyCompleted: done");
        didBuyBitcoin = true;
        Logging.INSTANCE.logPurchase(new PurchaseEvent()
                .putItemName("Bitcoin")
                .putSuccess(true));
    }

    @Override
    public void onShowTx(String txHash) {
        Bundle bundle = new Bundle();
        bundle.putString(BalanceFragment.KEY_TRANSACTION_HASH, txHash);
        TransactionDetailActivity.start(this, bundle);
    }

    private void activateIfReady() {
        if (isReady()) {
            setUiState(CONTENT);
        }
    }

    public boolean isReady() {
        return frontendInitialized && webViewLoginDetails != null;
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    /**
     * Handled in {@link MainActivity}
     */
    @Override
    public void onCompletedTrade(String txHash) {
        // No-op
    }

    private void showProgressDialog() {
        int message = R.string.please_wait;

        int year = YearClass.get(this);
        if (year < 2014) {
            // Phone too slow, show performance warning
            message = R.string.onboarding_buy_performance_warning;
        }

        if (!isFinishing()) {
            progress = new MaterialProgressDialog(this);
            progress.setMessage(message);
            progress.setOnCancelListener(dialog -> finish());
            progress.show();
        }
    }

    public void dismissProgressDialog() {
        if (progress != null && progress.isShowing()) {
            progress.dismiss();
            progress = null;
        }
    }

    @Override
    public void showSecondPasswordDialog() {
        AppCompatEditText editText = new AppCompatEditText(this);
        editText.setHint(R.string.password);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        FrameLayout frameLayout = ViewUtils.getAlertDialogPaddedView(this, editText);

        new AlertDialog.Builder(this, R.style.AlertDialogStyle)
                .setTitle(R.string.app_name)
                .setMessage(R.string.buy_second_password_prompt)
                .setView(frameLayout)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    ViewUtils.hideKeyboard(this);
                    getPresenter().generateMetadataNodes(editText.getText().toString());
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> finish())
                .create()
                .show();
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void setUiState(@UiState.UiStateDef int uiState) {
        switch (uiState) {
            case LOADING:
                showProgressDialog();
                break;
            case CONTENT:
                frontendJavascriptManager.activateMobileBuyFromJson(
                        webViewLoginDetails,
                        getPresenter().isNewlyCreated());
                dismissProgressDialog();
                break;
            case FAILURE:
                showToast(R.string.unexpected_error, ToastCustom.TYPE_ERROR);
                finish();
                break;
            case EMPTY:
                //no state
                dismissProgressDialog();
                break;
        }
    }

    @Override
    public void showToast(@StringRes int message, @ToastCustom.ToastType String toastType) {
        ToastCustom.makeText(this, getString(message), ToastCustom.LENGTH_SHORT, toastType);
    }

    @Override
    protected BuyPresenter createPresenter() {
        return buyPresenter;
    }

    @Override
    protected BuyView getView() {
        return this;
    }

    private static class EmailAwareWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return checkIfEmail(view, url) || super.shouldOverrideUrlLoading(view, url);
        }

        @TargetApi(Build.VERSION_CODES.N)
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            final String url = request.getUrl().toString();
            return checkIfEmail(view, url) || super.shouldOverrideUrlLoading(view, request);
        }

        private boolean checkIfEmail(WebView view, String url) {
            if (MailTo.isMailTo(url)) {
                final Context context = view.getContext();
                if (context != null) {
                    Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse(url));
                    context.startActivity(i);
                    view.reload();
                    return true;
                }
            }
            return false;
        }

    }

}
