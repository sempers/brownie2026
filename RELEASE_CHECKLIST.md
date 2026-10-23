# Brownie — выкладка в Google Play

## 1. Один раз: ключ подписи
```bash
keytool -genkeypair -v -keystore ~/keys/brownie-upload.jks \
  -alias upload -keyalg RSA -keysize 2048 -validity 10000
```
Создай `keystore.properties` в корне проекта (он в .gitignore):
```
storeFile=/home/you/keys/brownie-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```
Это **upload key**. В Play Console оставь включённым Play App Signing — тогда потеря этого ключа лечится сбросом через поддержку. Бэкап .jks и паролей всё равно сделай.

## 2. Сборка
Открыть папку в Android Studio → Gradle sync (если нет `gradlew` — Studio создаст его сама, либо `gradle wrapper` в корне).
```
./gradlew bundleRelease
```
Результат: `app/build/outputs/bundle/release/app-release.aab`. Перед каждой новой загрузкой — `versionCode + 1` в `app/build.gradle.kts`.

Проверить релизную сборку на телефоне до загрузки (R8 включён):
- старт/пауза, сворачивание, выключение экрана на 10+ минут;
- кнопки в уведомлении и на экране блокировки, Bluetooth-гарнитура;
- выдернуть наушники → пауза; входящий звонок → пауза и продолжение;
- смахнуть приложение из недавних во время игры → играет дальше; на паузе → уведомление исчезает;
- Android 15/16: ничего не залезает под навбар/статусбар.

## 3. Play Console
1. **Аккаунт разработчика** ($25, верификация личности).
2. **Создать приложение** → Brownie, бесплатное, App.
3. **App content** (Политика → Содержание приложения):
   - Privacy policy — URL с текстом из `store/PRIVACY_POLICY.md` (GitHub Pages, Gist, Google Sites — любая публичная страница).
   - Ads — «нет рекламы».
   - App access — «всё доступно без входа».
   - Content rating — анкета IARC (всё «нет» → 3+).
   - Target audience — 18+ (или 13+; **не** выбирай детские возрастные группы, иначе подпадёшь под Families policy).
   - Data safety — «приложение не собирает и не передаёт данные».
   - **Foreground service permissions** — тип `mediaPlayback`: описание («генерация шума продолжается в фоне и при выключенном экране, управление из уведомления»), влияние при прерывании («звук обрывается») и **обязательная ссылка на видео** (YouTube unlisted / Google Drive): запуск → свернуть → экран выключен, звук идёт → управление из уведомления.
   - Government apps / Financial features / Health — «нет».
4. **Store listing** — тексты и графика из `store/`, плюс 2–8 скриншотов телефона.
5. **Тестирование.** Если личный аккаунт создан после 13.11.2023: закрытое тестирование, **≥12 тестировщиков, 14 дней подряд**, потом заявка на production. Для аккаунта организации — можно сразу в production (лучше всё равно через Internal testing).
6. Загрузить AAB → релиз → отправить на ревью.

## 4. Что ещё можно (не обязательно)
- Таймер сна (самая частая просьба в отзывах у таких приложений).
- Перенести вёрстку на Material 3 / Compose.
- Crashlytics или хотя бы смотреть Android vitals в Play Console.
