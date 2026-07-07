import re

with open('app/src/main/java/com/blink/dtn/service/BLinkMeshService.kt', 'r') as f:
    content = f.read()

# Delete dtnRoutingJob declaration
content = content.replace("private var dtnRoutingJob: kotlinx.coroutines.Job? = null\n", "")
content = content.replace("dtnRoutingJob?.cancel()\n", "")

# Remove the dtnRoutingJob implementation
routing_job_pattern = re.compile(r'        // 2\. React to pending messages, active peers, and temporal backoff triggers\n        dtnRoutingJob = serviceScope\.launch \{\n.*?\n        \}\n', re.DOTALL)
content = routing_job_pattern.sub('', content)

# Remove backoffTrigger
content = content.replace("val backoffTrigger = kotlinx.coroutines.flow.MutableStateFlow(0L)\n", "")
content = content.replace("""                            if (result.failedMacs.isNotEmpty()) {
                                // Temporal trigger to wake up DTN router after backoff expires
                                launch {
                                    kotlinx.coroutines.delay(10_000L)
                                    backoffTrigger.value = System.currentTimeMillis()
                                }
                            }""", """                            if (result.failedMacs.isNotEmpty()) {
                                // Temporal trigger to wake up DTN router after backoff expires
                                launch {
                                    kotlinx.coroutines.delay(10_000L)
                                    bleMeshManager.triggerRelay()
                                }
                            }""")

with open('app/src/main/java/com/blink/dtn/service/BLinkMeshService.kt', 'w') as f:
    f.write(content)
