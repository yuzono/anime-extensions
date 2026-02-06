plugins {
    id("lib-multisrc")
}

baseVersionCode = 1

dependencies {
    api(project(":lib:vudeoextractor"))
    api(project(":lib:uqloadextractor"))
    api(project(":lib:streamwishextractor"))
    api(project(":lib:filemoonextractor"))
    api(project(":lib:streamlareextractor"))
    api(project(":lib:youruploadextractor"))
    api(project(":lib:streamtapeextractor"))
    api(project(":lib:doodextractor"))
    api(project(":lib:voeextractor"))
    api(project(":lib:okruextractor"))
    api(project(":lib:mp4uploadextractor"))
    api(project(":lib:mixdropextractor"))
    api(project(":lib:burstcloudextractor"))
    api(project(":lib:fastreamextractor"))
    api(project(":lib:upstreamextractor"))
    api(project(":lib:streamhidevidextractor"))
    api(project(":lib:streamsilkextractor"))
    api(project(":lib:vidguardextractor"))
    api(project(":lib:universalextractor"))
    api(project(":lib:vidhideextractor"))
}
