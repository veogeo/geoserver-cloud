package org.geoserver.cloud.restconfig.sldservice;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportResource;

/** Registers the SLDService plugin in the GeoServer Cloud REST configuration application. */
@Configuration(proxyBeanMethods = false)
@ImportResource("classpath:/org/veogeo/overlay/sldservice/sldservice-context.xml")
public class SldServiceConfiguration {}
