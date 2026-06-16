package org.multipaz.testapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import multipazproject.samples.testapp.generated.resources.Res
import multipazproject.samples.testapp.generated.resources.hedera_logomark
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

// Official Hedera brand gradient (brand.hedera.com): Ultraviolet → Azure.
private val HederaUltraviolet = Color(0xFF8259EF)
private val HederaAzure = Color(0xFF0031FF)

/**
 * A selectable x402 payment provider shown in the [X402PaymentsScreen] hub. A null [onClick] means
 * the provider is illustrative only ("Coming soon") — not yet wired into the wallet. When
 * [brandGradient]/[logo] are set the row is rendered as a branded card.
 */
private data class X402Provider(
    val name: String,
    val network: String,
    val onClick: (() -> Unit)? = null,
    val brandGradient: List<Color>? = null,
    val logo: DrawableResource? = null,
)

/**
 * Hub for x402 "agentic payment" credentials. x402 (HTTP 402) is a payment scheme many providers
 * can implement; "agentic" describes the category — an agent requests a payment and the user
 * authorizes it with a hardware-backed key. Hedera is wired in today; the others are shown to
 * convey that x402 is an extensible category, not yet implemented.
 */
@Composable
fun X402PaymentsScreen(
    onClickHedera: () -> Unit,
) {
    val providers = listOf(
        X402Provider(
            name = "Hedera",
            network = "Hedera testnet",
            onClick = onClickHedera,
            brandGradient = listOf(HederaUltraviolet, HederaAzure),
            logo = Res.drawable.hedera_logomark,
        ),
        X402Provider(name = "Coinbase", network = "Base · USDC"),
        X402Provider(name = "Solana", network = "Solana · USDC"),
        X402Provider(name = "Polygon", network = "Polygon · USDC"),
    )

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Hardware-backed credentials for x402 — the agent-payments protocol (HTTP 402). An " +
                "agent requests a payment; you authorize it with your device's secure hardware.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Providers", style = MaterialTheme.typography.titleSmall)
        for (provider in providers) {
            if (provider.brandGradient != null) {
                BrandedProviderCard(provider)
            } else {
                ComingSoonProviderCard(provider)
            }
        }
    }
}

/** A branded provider card: the provider's gradient, white logomark, and white text. */
@Composable
private fun BrandedProviderCard(provider: X402Provider) {
    val onClick = provider.onClick
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(provider.brandGradient!!))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (provider.logo != null) {
            Image(
                painter = painterResource(provider.logo),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(provider.name, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(
                provider.network,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

/** A plain provider card with a "Coming soon" tag for not-yet-implemented providers. */
@Composable
private fun ComingSoonProviderCard(provider: X402Provider) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(provider.name, style = MaterialTheme.typography.titleMedium)
                Text(provider.network, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Coming soon",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
