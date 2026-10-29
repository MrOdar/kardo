import { defineConfig } from 'vitest/config';

// Las pruebas de la SPA viven en src/test/js, al lado de las de Java, y no en
// un árbol propio: es el mismo proyecto y el mismo "donde están las pruebas".
export default defineConfig({
  test: {
    include: ['src/test/js/**/*.test.js'],
    environment: 'node'   // el arnés crea su propio jsdom por prueba
  }
});
