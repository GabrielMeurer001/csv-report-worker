# Relatórios CSV em segundo plano

Projeto de portfólio para receber um CSV fictício por uma API, processá-lo em segundo plano e consultar o relatório pelo identificador do job.

## Escopo inicial

- API e worker em Java com Spring Boot, RabbitMQ para entrega de jobs e PostgreSQL para registrar estado e resultado.
- Retornar um ID na solicitação e permitir consultar se o job está pendente, em processamento, concluído ou falhou.
- Usar um CSV fictício e documentar como reproduzir a demo localmente.

## Critérios da primeira demo

1. Um CSV válido gera um relatório consultável pela API.
2. A reentrega de uma mensagem com o mesmo ID produz um único relatório.
3. Uma falha persistente, depois das tentativas previstas, envia a mensagem à fila de mensagens mortas.

Estado atual: documentação inicial.
