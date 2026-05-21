/*
 *  Copyright (c) 2023 Metaform Systems, Inc.
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Metaform Systems, Inc. - initial API and implementation
 *
 */

import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage

plugins {
    `java-library`
    id("com.bmuschko.docker-remote-api") version "10.0.0"
    alias(libs.plugins.edc.build)
}

val edcBuildId = libs.plugins.edc.build.get().pluginId

allprojects {
    apply(plugin = edcBuildId)
}


val shadowPluginId = libs.plugins.shadow.get().pluginId
subprojects {
    afterEvaluate {
        if (project.plugins.hasPlugin(shadowPluginId) &&
            file("${project.projectDir}/src/main/docker/Dockerfile").exists()
        ) {
            //actually apply the plugin to the (sub-)project
            apply(plugin = "com.bmuschko.docker-remote-api")

            tasks.register("dockerize", DockerBuildImage::class) {
                val dockerContextDir = project.projectDir
                dockerFile.set(file("$dockerContextDir/src/main/docker/Dockerfile"))
                images.add("ghcr.io/eclipse-dataspace-hub/minimumviabledataspace/${project.name}:${project.version}")
                images.add("ghcr.io/eclipse-dataspace-hub/minimumviabledataspace/${project.name}:latest")
                // specify platform with the -Dplatform flag:
                if (System.getProperty("platform") != null)
                    platform.set(System.getProperty("platform"))
                buildArgs.put("JAR", "build/libs/${project.name}.jar")
                inputDir.set(file(dockerContextDir))
                dependsOn("shadowJar")
            }
        }
    }
}
