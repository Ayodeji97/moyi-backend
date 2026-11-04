package com.moyi.contracts

import io.swagger.v3.oas.models.OpenAPI

private const val REQUEST_OPERATION = "requestSemanticSearch"
private const val CANCEL_OPERATION = "cancelSemanticSearchRequest"
private const val BOND_RESPONSE = "BondResponse"
private const val PATCH_BOND_REQUEST = "PatchBondRequest"
private const val SETTING_PROPERTY = "semanticSearchEnabled"
private const val PENDING_PROPERTY = "pendingSemanticSearchRequest"

/**
 * The bond's semantic-search setting (FR-204, ADR-0054): off by one member through
 * `PATCH /bonds/{bondId}`, on again only with both, through `/bonds/{bondId}/semantic-search`, the
 * deletion request's shape. The schemas say a boolean and a pending request; this says what they
 * mean, and why turning it on is not a `PATCH`.
 */
internal fun documentSemanticSearch(api: OpenAPI) {
    api.paths.values.flatMap { it.readOperations() }.forEach { operation ->
        when (operation.operationId) {
            REQUEST_OPERATION -> operation.description = REQUEST_DESCRIPTION
            CANCEL_OPERATION -> operation.description = CANCEL_DESCRIPTION
        }
    }
    api.components.schemas[BOND_RESPONSE]?.properties?.let { properties ->
        properties[SETTING_PROPERTY]?.description = SETTING_DESCRIPTION
        properties[PENDING_PROPERTY]?.description = PENDING_DESCRIPTION
    }
    api.components.schemas[PATCH_BOND_REQUEST]
        ?.properties
        ?.get(SETTING_PROPERTY)
        ?.description = PATCH_DESCRIPTION
}

private const val SETTING_DESCRIPTION =
    "Whether the bond's entries are embedded so that search and \"Remember when\" also find by meaning. On by " +
        "default. While on, the words of each entry are sent to the embedding provider, once a provider is configured. " +
        "Either member turns it off alone; on again needs both."

private const val PENDING_DESCRIPTION =
    "A request by one member to turn semantic search back on, waiting for the other to agree through the same " +
        "`POST`. It lapses at `expiresAt`, seven days after it was made."

private const val PATCH_DESCRIPTION =
    "Only `false` is accepted: either member turns semantic search off alone, and every vector of the bond's entries " +
        "is deleted. `true` is 422 on this field (ASSERT_FALSE): turning it on needs both members, through " +
        "`POST /bonds/{bondId}/semantic-search`. `false` alone is accepted on a bond that has ended, too, so that " +
        "nothing more is sent to the provider; any other change to an ended bond is still 409."

private const val REQUEST_DESCRIPTION =
    "Asks to turn the bond's semantic search back on, or agrees to the other member's request. `202` with the bond: " +
        "on a paired bond nothing changes until the other member makes the same request, which turns it on at once; " +
        "on a bond still waiting for its second member it is on at once. Asking again, or asking while it is on, " +
        "changes nothing. Once on, the bond's entries are embedded in the background, and until then they are found " +
        "by their words alone. A bond that has ended is 409 BOND_ARCHIVED."

private const val CANCEL_DESCRIPTION =
    "Calls off a pending request to turn semantic search back on: the member who made it withdraws it, or the other " +
        "declines it. `204`; nothing pending is 404."
