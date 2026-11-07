# TopoBT actualizado

Proyecto Android para trabajo topográfico con Bluetooth SPP.

## Funciones añadidas

- Perfiles automáticos para Sokkia CX, Topcon GM, Topcon ES y Leica TS/TCRM, detectados por el nombre Bluetooth (con selección manual disponible).
- Lectura automática al conectar, con opción de pausarla, además de los botones **Medir** y **Medir y guardar**.
- Vista en vivo de la lectura recibida (distancia, Hz y V); E/N/Z se calculan cuando la estación y orientación están configuradas.
- Esquema cenital de la estación y línea de mira.
- Captura de lecturas desde los formularios de los programas.
- En cada programa, acciones **CALCULAR** (datos manuales), **MEDIR** y **REGISTRO** (medir y guardar).
- En Área, los registros medidos agregan vértices; al repetir el primer vértice o cerrar el polígono se muestran las áreas 2D y 3D.
- Programa **Sección vial / placa** con coordenadas medida y eje, kilometraje, azimut, pendientes, ángulo incluido, espesor, desplazamientos, ensanchamiento, orientación y gráfica de replanteo.
- En Sección vial, el punto se proyecta sobre el eje y su perpendicular definida por el azimut y el ángulo incluido; el alzado aplica pendiente longitudinal, pendiente de placa y espesor ingresados.
- Selector de objetivo Prisma/Sin prisma y altura de prisma para los cálculos de coordenadas.
- Cálculo de área de polígono, curvas circulares y espirales; entrada opcional de varios PI en filas.
- Carpetas de trabajo y exportación de puntos a TXT mediante el selector de archivos de Android.

## Comunicación de instrumentos

El perfil Sokkia/Topcon solicita lectura de distancia y ángulos mediante el comando binario estándar `0x11` terminado en CR/LF. El perfil Leica solicita Hz, V y distancia con `GET/M/WI21/WI22/WI31` en formato GSI. Las respuestas se interpretan y convierten a E/N/Z usando los datos configurados en **Estación**.

La app solicita lecturas automáticamente al conectar, usando el protocolo del perfil seleccionado. Los valores de coordenadas de estación, azimut de referencia y alturas de instrumento/prisma se mantienen como datos de configuración introducidos por el operador; la app no fabrica esos valores si el equipo no los entrega.

El selector Prisma/Sin prisma actualiza la altura objetivo usada por los cálculos; el cambio del modo EDM físico no se envía por Bluetooth en todos los modelos, así que se debe confirmar en la estación cuando su protocolo no exponga esa opción.

Los perfiles de las familias Topcon GM/ES y Leica TS/TCRM están implementados para sus protocolos de comunicación indicados, pero se deben confirmar en los equipos concretos antes de emplearlos en una jornada de medición. Mantener los datos originales del levantamiento y comprobar una distancia conocida al validar.

## Compilar

Abrir este directorio en Android Studio y compilar `app` como APK debug. El workflow incluido compila con Gradle 8.7 y JDK 17.
