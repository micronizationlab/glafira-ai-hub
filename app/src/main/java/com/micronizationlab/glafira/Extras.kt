fun dateLabel(date: String): String {
    if (date.isBlank()) return "Ранее"
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(System.currentTimeMillis() - 86400000L))
    return when (date) {
        today -> "Сегодня"
        yesterday -> "Вчера"
        else -> {
            try {
                val d = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(date)
                SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(d ?: Date())
            } catch (e: Exception) { date }
        }
    }
}
