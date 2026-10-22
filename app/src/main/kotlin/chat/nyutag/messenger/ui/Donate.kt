// Нютаг. «Поддержать Нютаг»: три разовые покупки из Google Play, те же, что у прежнего клиента (пакет один).
// Ничего не открывают в приложении: это просьба помочь оплатить серверы. Play Billing — единственная библиотека Google
// в приложении, по разрешению владельца: принимать деньги на Android иначе правила Play не дают.

package chat.nyutag.messenger.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.nyutag.messenger.BuildConfig
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/** Покупки «на чай»: товары из Play, покупка, «спасибо». Живёт, пока открыт экран. */
private class DonationStore(private val activity: Activity?) : PurchasesUpdatedListener {
    var products by mutableStateOf<List<ProductDetails>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var buying by mutableStateOf<String?>(null)
        private set
    var thanked by mutableStateOf(false)
        private set
    var unavailable by mutableStateOf(false)
        private set

    private val client: BillingClient? = activity?.let {
        BillingClient.newBuilder(it)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
    }

    fun start() {
        val client = client ?: return fail("нет activity")
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) return fail("подключение ${result.responseCode}")
                load(client)
                finishLeftovers(client)
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }

    private fun load(client: BillingClient) {
        val params = QueryProductDetailsParams.newBuilder().setProductList(PRODUCT_IDS.map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.INAPP).build()
        }).build()
        client.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync fail("товары ${result.responseCode}")
            products = details.productDetailsList.sortedBy { it.oneTimePurchaseOfferDetails?.priceAmountMicros ?: Long.MAX_VALUE }
            unavailable = products.isEmpty()
            loading = false
            if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "донат, товаров ${products.size}")
        }
    }

    /** Оплата прошла, а приложение закрылось раньше, чем покупку закрыли: закрываем сейчас, иначе Play вернёт деньги. */
    private fun finishLeftovers(client: BillingClient) {
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) purchases.forEach(::consume)
        }
    }

    fun buy(product: ProductDetails) {
        val activity = activity ?: return
        val client = client ?: return
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).build())
        ).build()
        buying = product.productId
        if (client.launchBillingFlow(activity, params).responseCode != BillingClient.BillingResponseCode.OK) {
            buying = null
            unavailable = true
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        buying = null
        if (result.responseCode != BillingClient.BillingResponseCode.OK) return
        purchases?.forEach {
            if (it.purchaseState == Purchase.PurchaseState.PURCHASED) thanked = true
            consume(it)
        }
    }

    /** Расходуемая покупка: после «спасибо» её можно купить снова. Ожидающую оплату (наличными и т. п.) не трогаем. */
    private fun consume(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        client?.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()) { _, _ -> }
    }

    fun close() {
        client?.endConnection()
    }

    private fun fail(reason: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("ui-check", "донат: $reason")
        unavailable = true
        loading = false
    }

    companion object {
        val PRODUCT_IDS = listOf("chat.nyutag.messenger.donate.small",
                                 "chat.nyutag.messenger.donate.medium",
                                 "chat.nyutag.messenger.donate.large")
    }
}

@Composable
fun DonateScreen(onClose: () -> Unit) {
    val words = LocalWords.current
    val palette = LocalPalette.current
    val activity = LocalActivity.current
    val store = remember { DonationStore(activity) }
    DisposableEffect(store) {
        store.start()
        onDispose { store.close() }
    }
    BackHandler(onBack = onClose)
    FormPage(words(P.donate), onClose, closeTitle = words(P.done)) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)) {
            NyutagLogo(76.dp)
            Box(Modifier.align(Alignment.BottomEnd).offset(8.dp, 8.dp).size(30.dp).clip(CircleShape).background(palette.gold),
                contentAlignment = Alignment.Center) {
                Text("♥", color = Color.White, fontSize = 17.sp)
            }
        }
        Text(words(P.donateBody), color = palette.ink, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (store.thanked) {
            Text(words(P.donateThanks), color = palette.read, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                 textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        if (store.loading) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(8.dp), color = palette.sky)
        } else if (store.unavailable) {
            Text(words(P.donateUnavailable), color = palette.quiet, fontSize = 13.sp, textAlign = TextAlign.Center,
                 modifier = Modifier.fillMaxWidth())
        }
        for (product in store.products) {
            val price = product.oneTimePurchaseOfferDetails?.formattedPrice.orEmpty()
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp)).background(palette.gold)
                    .clickable(enabled = store.buying == null) { store.buy(product) }.padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(product.name.ifEmpty { price }, color = palette.onGold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                if (store.buying == product.productId) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = palette.onGold, strokeWidth = 2.dp)
                } else {
                    Text(price, color = palette.onGold, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
