package app.cash.quickjs

class QuickJsException : RuntimeException {
	constructor(message: String?) : super(message)
	constructor(message: String?, cause: Throwable?) : super(message, cause)
}
