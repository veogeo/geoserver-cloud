# GeoServer Cloud REST SLDService overlay

This Cloud-owned module registers GeoServer's existing SLDService extension
inside the restconfig service. It contains only GeoServer Cloud and Spring
integration; it has no GeoNode imports or permission logic. GeoNode owns the
external /gsxt gateway and its dataset authorization.

The SLDService controllers and status bean are loaded from the plugin JAR. The
configuration class is under org.geoserver.cloud.restconfig, which is scanned
by the REST configuration application.

Build the runtime libraries after installing the matching
org.geoserver.extension:gs-sldservice artifact in the local Maven repository:

    ./veogeo/build-sldservice-libs.sh

The script writes generated JARs under the ignored veogeo/target directory.
The GeoNode local stack mounts these libraries read-only into restconfig.
