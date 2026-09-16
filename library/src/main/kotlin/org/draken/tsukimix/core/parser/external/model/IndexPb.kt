@file:Suppress("unused")

package org.draken.tsukimix.core.parser.external.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class IndexPb(
	@ProtoNumber(1) val name: String? = null,
	@ProtoNumber(2) val badgeLabel: String? = null,
	@ProtoNumber(3) val signingKey: String? = null,
	@ProtoNumber(4) val contact: ContactPb? = null,
	@ProtoNumber(101) val extensionList: ExtensionListPb? = null,
	@ProtoNumber(102) val extensionListUrl: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ContactPb(
	@ProtoNumber(1) val website: String? = null,
	@ProtoNumber(2) val discord: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ExtensionListPb(
	@ProtoNumber(1) val extensions: List<ExtensionPb> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ExtensionPb(
	@ProtoNumber(1) val name: String? = null,
	@ProtoNumber(2) val packageName: String? = null,
	@ProtoNumber(3) val resources: ResourcesPb? = null,
	@ProtoNumber(4) val extensionLib: String? = null,
	@ProtoNumber(5) val versionCode: Long? = null,
	@ProtoNumber(6) val versionName: String? = null,
	@ProtoNumber(7) val contentWarning: ContentWarningPb? = null,
	@ProtoNumber(8) val sources: List<SourcePb> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ResourcesPb(
	@ProtoNumber(1) val apkUrl: String? = null,
	@ProtoNumber(2) val iconUrl: String? = null,
	@ProtoNumber(501) val jarUrl: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SourcePb(
	@ProtoNumber(1) val id: Long? = null,
	@ProtoNumber(2) val name: String? = null,
	@ProtoNumber(3) val language: String? = null,
	@ProtoNumber(4) val homeUrl: String? = null,
	@ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
	@ProtoNumber(7) val message: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
enum class ContentWarningPb {
	@ProtoNumber(0) CONTENT_WARNING_UNSPECIFIED,
	@ProtoNumber(1) CONTENT_WARNING_SAFE,
	@ProtoNumber(2) CONTENT_WARNING_MIXED,
	@ProtoNumber(3) CONTENT_WARNING_NSFW,
}
