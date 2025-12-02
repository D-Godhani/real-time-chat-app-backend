import { NestFactory } from '@nestjs/core';
import { Logger } from 'nestjs-pino';
import * as cookieParser from 'cookie-parser';
import { AppModule } from './app.module';
import { ValidationPipe } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';

async function bootstrap() {
  const app = await NestFactory.create(AppModule, { bufferLogs: true });
  app.useGlobalPipes(new ValidationPipe());
  app.useLogger(app.get(Logger));
  app.use(cookieParser());
  const configService = app.get(ConfigService);
  const allowedOrigins =
    configService.get<string>('WEB_APP_URL')?.split(',').map((origin) => origin.trim()) ??
    true;
  app.enableCors({
    origin: allowedOrigins,
    credentials: true,
  });
  await app.listen(configService.getOrThrow('PORT'));
}
bootstrap();
