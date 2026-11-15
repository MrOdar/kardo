import { defineConfig } from 'vitest/config';

// Las pruebas de la interfaz viven en src/test/js, dentro del mismo módulo que
// lo que prueban: desde que el front se despliega aparte, este es su proyecto.
export default defineConfig({
  test: {
    include: ['src/test/js/**/*.test.js'],
    environment: 'node'   // el arnés crea su propio jsdom por prueba
  }
});
