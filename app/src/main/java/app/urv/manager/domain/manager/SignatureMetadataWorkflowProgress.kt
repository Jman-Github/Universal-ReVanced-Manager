package app.urv.manager.domain.manager

/** Live injection state retained by the job, independently of its progress screen. */
data class SignatureMetadataWorkflowProgress(
    val running: Boolean = false,
    val completed: Boolean = false,
    val error: String? = null,
    val stage: SignatureMetadataInjectorStage = SignatureMetadataInjectorStage.ANALYZING,
    val logEntries: List<String> = emptyList(),
    val logRevision: Long = 0L,
    val logSessionId: Long = 0L
) {
    fun appendLog(message: String): SignatureMetadataWorkflowProgress = copy(
        logEntries = (logEntries + message.take(8_000).lineSequence().toList()).takeLast(300),
        logRevision = logRevision + 1L
    )
}
