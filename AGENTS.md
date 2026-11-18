# Regras do `csv-report-worker`

- Execute a demo local pelo Docker Compose. Mantenha a API e o worker no mesmo aplicativo Spring Boot e um consumidor por vez enquanto a demo depender desse modelo de execução.
- Use apenas dados fictícios nos exemplos e testes. As credenciais do Compose são para uso local. Mantenha as portas da demo vinculadas ao loopback.
- Faça alterações no banco por novas migrações Flyway. Preserve migrações já aplicadas e a capacidade de consultar jobs existentes.
- Use `BigDecimal` do parse à agregação. Mantenha valores monetários com duas casas e preserve o contrato JSON de strings decimais.
- Publique apenas o UUID após o commit do job. Confirme o consumo após persistir o estado final. Preserve estados terminais diante de reentregas.
- Preserve o número físico da linha nos erros de CSV. Separe falhas permanentes de entrada das falhas operacionais que podem exigir nova tentativa.
- Faça mudanças pequenas com um cenário verificável por etapa. Rode os testes Maven ao alterar o backend e `scripts/verify-demo.ps1` ao alterar API, fila ou persistência. Rode `npm run build` ao alterar a interface. Atualize os exemplos do README se o modo de execução ou o contrato mudar.
