package local.kuaixiu.data

/**
 * 模型分类器：把 /v1/models 拉回的 id 列表按 文本 / 生图 分列。
 * 规则移植自 tuwen-editor 的 modelCatalog.ts（启发式兜底 + 已知生图模型目录）。
 */
object ModelClassifier {

    private val IMAGE_RE = Regex(
        "image|dall[-_]?e|flux|seedream|seedance|cogview|wan\\d|stable-diffusion|sd3|hidream|kolors|irag|photon",
        RegexOption.IGNORE_CASE,
    )

    /** 已知生图模型目录（关键词未收录的补充；商汤 u1 系实测为生图） */
    private val KNOWN_IMAGE = Regex("^sensenova-u1", RegexOption.IGNORE_CASE)

    fun isImageModel(id: String): Boolean = KNOWN_IMAGE.containsMatchIn(id) || IMAGE_RE.containsMatchIn(id)

    fun classify(ids: List<String>): Pair<List<String>, List<String>> {
        val text = ids.filterNot { isImageModel(it) }.sorted()
        val image = ids.filter { isImageModel(it) }.sorted()
        return text to image
    }
}
