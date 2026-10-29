---
name: Jornada
description: Registro personal de jornadas en Android
colors:
  primary: "#245C48"
  on-primary: "#FFFFFF"
  primary-container: "#D9EBDD"
  on-primary-container: "#123B2B"
  background: "#FAF8F1"
  on-background: "#202B24"
  surface-variant: "#ECEEE5"
  on-surface-variant: "#505C52"
  outline: "#737D72"
  error: "#A52E2E"
  dark-primary: "#A4D4B8"
  dark-background: "#141C17"
  dark-on-background: "#E2E8DF"
typography:
  display:
    fontFamily: "Roboto, sans-serif"
    fontSize: "57sp"
    fontWeight: 300
    lineHeight: 1.12
  heading:
    fontFamily: "Roboto, sans-serif"
    fontSize: "32sp"
    fontWeight: 500
    lineHeight: 1.25
  body:
    fontFamily: "Roboto, sans-serif"
    fontSize: "16sp"
    fontWeight: 400
    lineHeight: 1.5
rounded:
  status: "8dp"
  calendar-cell: "12dp"
  punch: "16dp"
spacing:
  small: "8dp"
  field: "16dp"
  section: "20dp"
  page: "24dp"
components:
  button-punch:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.punch}"
    height: "64dp minimum"
  status:
    backgroundColor: "{colors.primary-container}"
    textColor: "{colors.on-primary-container}"
    rounded: "{rounded.status}"
    padding: "6dp 10dp"
---

## Overview

Interfaz nativa de operación: fichar, revisar y corregir. La dirección aprobada utiliza marfil, tinta oscura y verde sobrio. Material 3 gobierna navegación, controles, hojas inferiores y estados. Copia funcional, sin lemas.

## Colors

Los colores de la implementación se asignan a roles de `MaterialTheme.colorScheme`, con esquemas claro y oscuro completos. El verde señala acción principal, selección y estado registrado. Los avisos usan los roles de error; se explican también mediante texto. La navegación seleccionada utiliza la misma familia verde, sin el violeta predeterminado.

## Typography

Una familia del sistema, con la escala Material 3 y medidas en sp. `displayLarge` solo para la hora, `headlineLarge` para el estado de hoy, `headlineMedium` para los demás encabezados, `titleLarge` para secciones y `bodyMedium` para ayuda. Se respeta el tamaño de letra del sistema.

## Layout

Una columna móvil, márgenes de 24dp (12dp en calendario), grupos separados 16–20dp. Anchura máxima del contenido de 900dp. Navegación inferior para cuatro destinos; a partir de 700dp se sustituye por un rail lateral. Las pantallas y formularios desplazan su contenido; se aplican los insets del sistema y teclado.

## Elevation & Depth

Superficies y elevación tonal de Material 3. No se añaden sombras decorativas. Separadores de sección y listas abiertas, evitando encerrar cada dato en una tarjeta.

## Shapes

Botón de fichaje de 16dp de radio y mínimo 64dp de alto. Celdas de calendario de 12dp; indicadores de estado de 8dp. El resto de componentes conserva la forma de Material 3.

## Components

`NavigationBar`/`NavigationRail`, `TopAppBar`, `Button`, `OutlinedButton`, `FilterChip`, `Switch`, `Snackbar` y `ModalBottomSheet`. Fechas y horas con selectores Android. Diálogos para confirmar la restauración de datos. Iconos Material y un icono vectorial propio; ningún recurso remoto.

## Do's and Don'ts

- Mantener la acción de fichar visible y única en Hoy.
- Mostrar las diferencias en minutos y explicar su estado con palabras.
- Guardar los formularios solo tras validar; conservarlos abiertos si falla el guardado.
- Utilizar nombres de tareas y datos concretos; evitar frases promocionales.
- No colorear una justificación como si hubiera cambiado las horas originales.
- No añadir ilustraciones, tramas, efectos o animaciones que dificulten el registro.
