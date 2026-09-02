package com.vizuzik.app.domain.model

/**
 * Origine d'un élément musical. La V0.1 n'utilise que [LOCAL] ; [DEEZER] existe
 * déjà dans le modèle pour qu'une future source distante n'oblige pas à
 * retoucher [Track], [Album] ou [Artist]. Les identifiants de chaque source
 * sont préfixés (ex. "local:42") pour ne jamais entrer en collision.
 */
enum class MusicSourceType(val idPrefix: String) {
    LOCAL("local"),
    DEEZER("deezer"),
}
