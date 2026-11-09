package com.moyi.bond.api

/**
 * The detail of the bond's one `404` (T-02, ADR-0026): a bond the caller is not in, no such bond, or an id that is
 * not one. Written once, here, so that every route that answers for a bond outside `bond` (the member report's
 * replay and its unreadable id, ADR-0053 decision 14) says exactly what `bond`'s own guard says, byte for byte: two
 * wordings would tell a caller which check refused them.
 */
const val BOND_NOT_FOUND_DETAIL = "That bond was not found."
