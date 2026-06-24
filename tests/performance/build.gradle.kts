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
}

dependencies {
    testImplementation(libs.edc.ext.jsonld)
    testImplementation(libs.edc.identity.did.core)
    testImplementation(libs.edc.spi.dcp)
    testImplementation(libs.edc.ih.credentials)
    testImplementation(libs.edc.service.identity.trust)
    testImplementation(libs.edc.core.crypto)

    testImplementation(libs.edc.junit)
    testImplementation(testFixtures(libs.edc.lib.jws2020))
    testImplementation(testFixtures(libs.edc.identity.vc.ldp))
    testImplementation(testFixtures(libs.edc.identity.vc.jwt))
}
