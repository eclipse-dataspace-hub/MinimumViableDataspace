/*
*  Copyright (c) 2023 Bayerische Motoren Werke Aktiengesellschaft (BMW AG)
*
*  This program and the accompanying materials are made available under the
*  terms of the Apache License, Version 2.0 which is available at
*  https://www.apache.org/licenses/LICENSE-2.0
*
*  SPDX-License-Identifier: Apache-2.0
*
*  Contributors:
*       Bayerische Motoren Werke Aktiengesellschaft (BMW AG) - Initial API and Implementation
*
*/

plugins {
    `java-library`
    id("application")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":extensions:signaling-auth-none"))
    implementation(libs.edc.boot)
    implementation(libs.edc.core.runtime)
    implementation(libs.edc.ext.http)
    implementation(libs.dataplane.sdk)
    implementation(libs.edc.spi.core)
    implementation(libs.edc.lib.core)

    // needed for the key seed extension
    runtimeOnly(libs.tink)
    implementation(libs.nimbus.jwt)

    // for kubernetes probes
    runtimeOnly(libs.edc.api.observability)

    runtimeOnly(libs.edc.core.participantcontext.config)
    runtimeOnly(libs.edc.vault.hashicorp)
    runtimeOnly(libs.edc.bom.dataplane.sql)
}

tasks.shadowJar {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    archiveFileName.set("${project.name}.jar")
}

application {
    mainClass.set("org.eclipse.edc.boot.system.runtime.BaseRuntime")
}

edcBuild {
    publish.set(false)
}
