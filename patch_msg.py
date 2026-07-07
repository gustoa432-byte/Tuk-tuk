with open("/app/applet/app/src/main/java/com/blink/dtn/db/Message.kt", "r") as f:
    text = f.read()

text = text.replace("import androidx.room.Entity", "import androidx.room.Entity\nimport androidx.room.ForeignKey\nimport androidx.room.Index")

schema = """@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["conversationId"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["conversationId"])
    ]
)"""

text = text.replace('@Entity(tableName = "messages")', schema)

# add conversationId property
prop = """    var retryCount: Int = 0,

    @Transient @ColumnInfo(name = "conversationId") var conversationId: String = ""
) {"""
text = text.replace('    var retryCount: Int = 0\n) {', prop)

with open("/app/applet/app/src/main/java/com/blink/dtn/db/Message.kt", "w") as f:
    f.write(text)
