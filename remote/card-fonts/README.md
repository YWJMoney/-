# 장터 카드 글꼴 (밤낮 앱이 쓰는 공개 글꼴의 고친 판)

밤낮 앱의 장터는 작품마다 예시 폰 그림(카드)을 그려요. 작품이 공개 글꼴을 쓰면 카드도 그 글꼴로 그리는데, 카드에 나오는 글자 513자(숫자·영문·기호 139자와 한글 374자)만 담은 작은 글꼴을 여기에 둬요. 앱은 장터를 볼 때 이 파일만 받고, 글꼴 전체는 사용자가 그 글꼴을 고르거나 그 글꼴을 쓰는 작품을 입을 때만 받아요.

- 폴더: `4cf371a3/` (카드에 나오는 글자 목록이 바뀌면 판 번호가 바뀌어 새 폴더가 생겨요). 앱은 `remote/card-fonts/<판>/<id>-r.ttf`(보통 굵기)와 `-b.ttf`(굵게)를 받고, 앱에 적힌 sha256과 같을 때만 써요.
- `4cf371a3/manifest.json`: 파일마다 원본 파일, 굵기, 크기, sha256, 출처.
- `4cf371a3/licenses/<id>.txt`: 그 글꼴의 라이선스 원문(저작권 줄 포함). 글꼴 파일 안의 저작권(name 0)·라이선스(name 13·14) 칸도 그대로 두었어요.

## 무엇을 바꿨나요 (고친 판)

모든 파일은 [google/fonts](https://github.com/google/fonts) 커밋 `c30b221beb0c1da59d563feb91ec8656a1fa972a`의 원본에서 같은 방법으로 만들었어요(밤낮 저장소의 `tools/card_fonts.py`, 같은 원본과 글자면 같은 파일이 나와요).

1. 카드에 나오는 글자만 남겼어요(나머지 글자와 힌팅을 뺌). 가변 글꼴은 한 굵기로 고정했어요(보통 400, 없으면 500·300, 굵게 700).
2. 이름을 모두 `BN Card <번호>`로 바꿨어요(name 1·3·4·6·16·17·18·21·22·25). SIL OFL 1.1에서 글자를 빼는 것은 고친 판이라 예약 글꼴 이름(RFN)을 쓸 수 없고([OFL-FAQ 1.1-update8](https://openfontlicense.org/ofl-faq/) 2.5·2.6, OFL 3항), 원래 이름이 조금도 겹치지 않게 번호만 썼어요.
3. 고쳤다는 글을 파일 안 설명 칸(name 10)에 적었어요: `Changed by bamnat (2026): only the letters a bamnat market card shows, hinting removed, renamed 'BN Card NN' …`.

## 라이선스 조건과 지킨 것

- **SIL Open Font License 1.1** (69개): 고친 판도 이 라이선스로만 나가요(5항). 각 파일에 저작권과 라이선스를 함께 둬요(2항: `licenses/<id>.txt`와 파일 안 칸). 예약 이름을 쓰지 않아요(3항, 위 표의 RFN 칸). 원저작자 이름으로 고친 판을 홍보하지 않고, 아래 표는 출처를 밝히는 감사의 뜻이에요(4항, OFL-FAQ 1.19·5.2). 글꼴만 따로 팔지 않아요(1항).
- **Apache License 2.0** (Permanent Marker 1개): 라이선스 사본을 함께 두고(4(a), `licenses/permanentmarker.txt`), 고친 파일에 고쳤다는 글을 넣고(4(b), name 10과 이 글), 원본의 저작권·상표·저작자 표시를 그대로 둬요(4(c)). 원본 폴더에 NOTICE 파일은 없어요(4(d) 해당 없음).

## 글꼴마다 (번호·바꾼 이름, 원본, 라이선스, 예약 이름)

| 번호·바꾼 이름 | 원본 글꼴 | 라이선스 | 예약 이름(RFN) | 원본(google/fonts 폴더 · 위 저장소와 커밋) | 굵기·크기 | 함께 두는 글 |
|---|---|---|---|---|---|---|
| BN Card 01 | Noto Sans KR (`notosanskr`) | SIL OFL 1.1 | Source | `ofl/notosanskr` · [notofonts/noto-cjk](https://github.com/notofonts/noto-cjk) `523d033d6c` | 400 132 KB · 700 132 KB | `licenses/notosanskr.txt` |
| BN Card 02 | Nanum Gothic (`nanumgothic`) | SIL OFL 1.1 | Nanum, Naver Nanum, NanumGothic, Naver NanumGothic, NanumMyeongjo, Naver NanumMyeongjo, NanumBrush, Naver NanumBrush, NanumPen, Naver NanumPen | `ofl/nanumgothic` · [googlefonts/nanumgothic](https://github.com/googlefonts/nanumgothic) `1893597aed` | 400 121 KB · 700 122 KB | `licenses/nanumgothic.txt` |
| BN Card 03 | Gothic A1 (`gothica1`) | SIL OFL 1.1 | 없음 | `ofl/gothica1` · [hanyangfont/Gothic](https://github.com/hanyangfont/Gothic) `599d7e34ff` | 400 98 KB · 700 99 KB | `licenses/gothica1.txt` |
| BN Card 04 | IBM Plex Sans KR (`ibmplexsanskr`) | SIL OFL 1.1 | Plex | `ofl/ibmplexsanskr` · [googlefonts/plex](https://github.com/googlefonts/plex) `1331d4514d` | 400 117 KB · 700 114 KB | `licenses/ibmplexsanskr.txt` |
| BN Card 05 | Gowun Dodum (`gowundodum`) | SIL OFL 1.1 | 없음 | `ofl/gowundodum` · [yangheeryu/Gowun-Dodum](https://github.com/yangheeryu/Gowun-Dodum) `6d9ef10fc7` | 400 183 KB | `licenses/gowundodum.txt` |
| BN Card 06 | Orbit (`orbit`) | SIL OFL 1.1 | 없음 | `ofl/orbit` · [JAMO-TYPEFACE/Orbit](https://github.com/JAMO-TYPEFACE/Orbit) `95122395c6` | 400 56 KB | `licenses/orbit.txt` |
| BN Card 07 | Sunflower (`sunflower`) | SIL OFL 1.1 | 없음 | `ofl/sunflower` · [googlefonts/sunflower](https://github.com/googlefonts/sunflower) `ee6d06ce48` | 500 53 KB · 700 54 KB | `licenses/sunflower.txt` |
| BN Card 08 | Noto Serif KR (`notoserifkr`) | SIL OFL 1.1 | 없음 | `ofl/notoserifkr` · [https://www.github.com/notofonts/noto-cjk](https://www.github.com/notofonts/noto-cjk) `985fa52c81` | 400 286 KB · 700 287 KB | `licenses/notoserifkr.txt` |
| BN Card 09 | Nanum Myeongjo (`nanummyeongjo`) | SIL OFL 1.1 | Nanum, Naver Nanum, NanumGothic, Naver NanumGothic, NanumMyeongjo, Naver NanumMyeongjo, NanumBrush, Naver NanumBrush, NanumPen, Naver NanumPen | `ofl/nanummyeongjo` · [googlefonts/googlefontdirectory-hg](https://github.com/googlefonts/googlefontdirectory-hg) `52f780bc9d` | 400 163 KB · 700 168 KB | `licenses/nanummyeongjo.txt` |
| BN Card 10 | Gowun Batang (`gowunbatang`) | SIL OFL 1.1 | 없음 | `ofl/gowunbatang` · [yangheeryu/Gowun-Batang](https://github.com/yangheeryu/Gowun-Batang) `4e73f5a9a0` | 400 212 KB · 700 211 KB | `licenses/gowunbatang.txt` |
| BN Card 11 | Song Myung (`songmyung`) | SIL OFL 1.1 | 없음 | `ofl/songmyung` · [googlefonts/songmyung](https://github.com/googlefonts/songmyung) `34d21808dd` | 400 169 KB | `licenses/songmyung.txt` |
| BN Card 12 | Hahmlet (`hahmlet`) | SIL OFL 1.1 | 없음 | `ofl/hahmlet` · [hyper-type/hahmlet/](https://github.com/hyper-type/hahmlet/) `1065411449` | 400 203 KB · 700 204 KB | `licenses/hahmlet.txt` |
| BN Card 13 | Diphylleia (`diphylleia`) | SIL OFL 1.1 | 없음 | `ofl/diphylleia` · [JAMO-TYPEFACE/Diphylleia](https://github.com/JAMO-TYPEFACE/Diphylleia) `6a20531aa2` | 400 177 KB | `licenses/diphylleia.txt` |
| BN Card 14 | Jua (`jua`) | SIL OFL 1.1 | 없음 | `ofl/jua` · [googlefonts/jua](https://github.com/googlefonts/jua) `9dce1f1706` | 400 207 KB | `licenses/jua.txt` |
| BN Card 15 | Dongle (`dongle`) | SIL OFL 1.1 | 없음 | `ofl/dongle` · [yangheeryu/Dongle](https://github.com/yangheeryu/Dongle) `f7127c4d24` | 400 119 KB · 700 121 KB | `licenses/dongle.txt` |
| BN Card 16 | Cute Font (`cutefont`) | SIL OFL 1.1 | 없음 | `ofl/cutefont` · [googlefonts/cutefont](https://github.com/googlefonts/cutefont) `759c5c5d25` | 400 90 KB | `licenses/cutefont.txt` |
| BN Card 17 | Stylish (`stylish`) | SIL OFL 1.1 | 없음 | `ofl/stylish` · [googlefonts/stylish](https://github.com/googlefonts/stylish) `e0b178c4cb` | 400 155 KB | `licenses/stylish.txt` |
| BN Card 18 | Gamja Flower (`gamjaflower`) | SIL OFL 1.1 | 없음 | `ofl/gamjaflower` · [googlefonts/gamjaflower](https://github.com/googlefonts/gamjaflower) `c98981a4f4` | 400 174 KB | `licenses/gamjaflower.txt` |
| BN Card 19 | Hi Melody (`himelody`) | SIL OFL 1.1 | 없음 | `ofl/himelody` · [googlefonts/himelody](https://github.com/googlefonts/himelody) `a322a95b16` | 400 172 KB | `licenses/himelody.txt` |
| BN Card 20 | Poor Story (`poorstory`) | SIL OFL 1.1 | 없음 | `ofl/poorstory` · [googlefonts/poorstory](https://github.com/googlefonts/poorstory) `b2d4c0cc67` | 400 66 KB | `licenses/poorstory.txt` |
| BN Card 21 | Single Day (`singleday`) | SIL OFL 1.1 | 없음 | `ofl/singleday` · (METADATA에 없음) | 400 91 KB | `licenses/singleday.txt` |
| BN Card 22 | Nanum Pen Script (`nanumpen`) | SIL OFL 1.1 | Nanum, Naver Nanum, NanumGothic, Naver NanumGothic, NanumMyeongjo, Naver NanumMyeongjo, NanumBrush, Naver NanumBrush, NanumPen, Naver NanumPen | `ofl/nanumpenscript` · [googlefonts/nanumpenscript](https://github.com/googlefonts/nanumpenscript) `404860478f` | 400 181 KB | `licenses/nanumpen.txt` |
| BN Card 23 | Nanum Brush Script (`nanumbrush`) | SIL OFL 1.1 | Nanum, Naver Nanum, NanumGothic, Naver NanumGothic, NanumMyeongjo, Naver NanumMyeongjo, NanumBrush, Naver NanumBrush, NanumPen, Naver NanumPen | `ofl/nanumbrushscript` · [googlefonts/nanumbrushscript](https://github.com/googlefonts/nanumbrushscript) `5b703ee26d` | 400 226 KB | `licenses/nanumbrush.txt` |
| BN Card 24 | Gaegu (`gaegu`) | SIL OFL 1.1 | 없음 | `ofl/gaegu` · (METADATA에 없음) | 400 240 KB · 700 246 KB | `licenses/gaegu.txt` |
| BN Card 25 | Yeon Sung (`yeonsung`) | SIL OFL 1.1 | 없음 | `ofl/yeonsung` · [googlefonts/yeonsung](https://github.com/googlefonts/yeonsung) `8979fd9bff` | 400 220 KB | `licenses/yeonsung.txt` |
| BN Card 26 | Dokdo (`dokdo`) | SIL OFL 1.1 | 없음 | `ofl/dokdo` · [googlefonts/dokdo](https://github.com/googlefonts/dokdo) `47a91e8d46` | 400 215 KB | `licenses/dokdo.txt` |
| BN Card 27 | East Sea Dokdo (`eastseadokdo`) | SIL OFL 1.1 | 없음 | `ofl/eastseadokdo` · [googlefonts/eastseadokdo](https://github.com/googlefonts/eastseadokdo) `7826832c41` | 400 483 KB | `licenses/eastseadokdo.txt` |
| BN Card 28 | Kirang Haerang (`kiranghaerang`) | SIL OFL 1.1 | 없음 | `ofl/kiranghaerang` · [woowabros/KirangHaerang](https://github.com/woowabros/KirangHaerang) `544ae6224f` | 400 462 KB | `licenses/kiranghaerang.txt` |
| BN Card 29 | Black And White Picture (`bwpicture`) | SIL OFL 1.1 | 없음 | `ofl/blackandwhitepicture` · [googlefonts/blackandwhitepicture](https://github.com/googlefonts/blackandwhitepicture) `75469c6eb2` | 400 342 KB | `licenses/bwpicture.txt` |
| BN Card 30 | Black Han Sans (`blackhansans`) | SIL OFL 1.1 | 없음 | `ofl/blackhansans` · [googlefonts/blackhansans](https://github.com/googlefonts/blackhansans) `41b7d933d1` | 400 53 KB | `licenses/blackhansans.txt` |
| BN Card 31 | Do Hyeon (`dohyeon`) | SIL OFL 1.1 | 없음 | `ofl/dohyeon` · [googlefonts/dohyeon](https://github.com/googlefonts/dohyeon) `8607d13340` | 400 61 KB | `licenses/dohyeon.txt` |
| BN Card 32 | Gugi (`gugi`) | SIL OFL 1.1 | 없음 | `ofl/gugi` · [googlefonts/gugi](https://github.com/googlefonts/gugi) `cb3b662a31` | 400 84 KB | `licenses/gugi.txt` |
| BN Card 33 | Bagel Fat One (`bagelfatone`) | SIL OFL 1.1 | 없음 | `ofl/bagelfatone` · [JAMO-TYPEFACE/BagelFat](https://github.com/JAMO-TYPEFACE/BagelFat) `d8dd4e8b5d` | 400 123 KB | `licenses/bagelfatone.txt` |
| BN Card 34 | Gasoek One (`gasoekone`) | SIL OFL 1.1 | 없음 | `ofl/gasoekone` · [JAMO-TYPEFACE/Gasoek](https://github.com/JAMO-TYPEFACE/Gasoek) `f6b875c95a` | 400 73 KB | `licenses/gasoekone.txt` |
| BN Card 35 | Moirai One (`moiraione`) | SIL OFL 1.1 | 없음 | `ofl/moiraione` · [JAMO-TYPEFACE/Moirai](https://github.com/JAMO-TYPEFACE/Moirai) `2da4839590` | 400 178 KB | `licenses/moiraione.txt` |
| BN Card 36 | Grandiflora One (`grandifloraone`) | SIL OFL 1.1 | 없음 | `ofl/grandifloraone` · [JAMO-TYPEFACE/Grandiflora](https://github.com/JAMO-TYPEFACE/Grandiflora) `5e5d39d3d4` | 400 153 KB | `licenses/grandifloraone.txt` |
| BN Card 37 | Nanum Gothic Coding (`nanumgothiccoding`) | SIL OFL 1.1 | Nanum, Naver Nanum, NanumGothic, Naver NanumGothic, NanumMyeongjo, Naver NanumMyeongjo, NanumBrush, Naver NanumBrush, NanumPen, Naver NanumPen | `ofl/nanumgothiccoding` · [naver/nanumfont](https://github.com/naver/nanumfont) `770142018d` | 400 109 KB · 700 110 KB | `licenses/nanumgothiccoding.txt` |
| BN Card 38 | Bebas Neue (`bebasneue`) | SIL OFL 1.1 | 없음 | `ofl/bebasneue` · [dharmatype/Bebas-Neue](https://github.com/dharmatype/Bebas-Neue) `686d14af64` | 400 17 KB | `licenses/bebasneue.txt` |
| BN Card 39 | Oswald (`oswald`) | SIL OFL 1.1 | 없음 | `ofl/oswald` · [googlefonts/OswaldFont](https://github.com/googlefonts/OswaldFont) `89795261ac` | 400 18 KB · 700 18 KB | `licenses/oswald.txt` |
| BN Card 40 | Anton (`anton`) | SIL OFL 1.1 | 없음 | `ofl/anton` · [googlefonts/AntonFont](https://github.com/googlefonts/AntonFont) `beb92fcad8` | 400 22 KB | `licenses/anton.txt` |
| BN Card 41 | Archivo Black (`archivoblack`) | SIL OFL 1.1 | 없음 | `ofl/archivoblack` · [Omnibus-Type/ArchivoBlack](https://github.com/Omnibus-Type/ArchivoBlack) `95df3c54d8` | 400 15 KB | `licenses/archivoblack.txt` |
| BN Card 42 | Montserrat (`montserrat`) | SIL OFL 1.1 | 없음 | `ofl/montserrat` · [JulietaUla/Montserrat](https://github.com/JulietaUla/Montserrat) `cc8daf2e70` | 400 58 KB · 700 58 KB | `licenses/montserrat.txt` |
| BN Card 43 | Poppins (`poppins`) | SIL OFL 1.1 | 없음 | `ofl/poppins` · [itfoundry/Poppins](https://github.com/itfoundry/Poppins) `738d9d691b` | 400 12 KB · 700 12 KB | `licenses/poppins.txt` |
| BN Card 44 | Quicksand (`quicksand`) | SIL OFL 1.1 | Quicksand | `ofl/quicksand` · [ThomasJockin/QuicksandFamily](https://github.com/ThomasJockin/QuicksandFamily) `02ead1c009` | 400 24 KB · 700 24 KB | `licenses/quicksand.txt` |
| BN Card 45 | Comfortaa (`comfortaa`) | SIL OFL 1.1 | Comfortaa | `ofl/comfortaa` · [alexeiva/comfortaa](https://github.com/alexeiva/comfortaa) `4aa21cfb7b` | 400 23 KB · 700 23 KB | `licenses/comfortaa.txt` |
| BN Card 46 | Fredoka (`fredoka`) | SIL OFL 1.1 | 없음 | `ofl/fredoka` · [hafontia-zz/Fredoka-One](https://github.com/hafontia-zz/Fredoka-One) `35c584ff23` | 400 25 KB · 700 24 KB | `licenses/fredoka.txt` |
| BN Card 47 | Nunito (`nunito`) | SIL OFL 1.1 | 없음 | `ofl/nunito` · [googlefonts/nunito](https://github.com/googlefonts/nunito) `8c6a9bb973` | 400 26 KB · 700 26 KB | `licenses/nunito.txt` |
| BN Card 48 | Rubik (`rubik`) | SIL OFL 1.1 | 없음 | `ofl/rubik` · [googlefonts/rubik](https://github.com/googlefonts/rubik) `9167c98fa8` | 400 37 KB · 700 37 KB | `licenses/rubik.txt` |
| BN Card 49 | Josefin Sans (`josefinsans`) | SIL OFL 1.1 | Josefin Sans | `ofl/josefinsans` · [googlefonts/josefinsans](https://github.com/googlefonts/josefinsans) `bf6907e3a3` | 400 17 KB · 700 17 KB | `licenses/josefinsans.txt` |
| BN Card 50 | Playfair Display (`playfair`) | SIL OFL 1.1 | Playfair Display | `ofl/playfairdisplay` · [clauseggers/Playfair](https://github.com/clauseggers/Playfair) `80a3341019` | 400 54 KB · 700 54 KB | `licenses/playfair.txt` |
| BN Card 51 | Lora (`lora`) | SIL OFL 1.1 | Lora | `ofl/lora` · [cyrealtype/Lora-Cyrillic](https://github.com/cyrealtype/Lora-Cyrillic) `403b1a66ca` | 400 30 KB · 700 30 KB | `licenses/lora.txt` |
| BN Card 52 | Abril Fatface (`abrilfatface`) | SIL OFL 1.1 | Abril" and "Abril Fatface | `ofl/abrilfatface` · [googlefonts/abrilfatface](https://github.com/googlefonts/abrilfatface) `9d1486e101` | 400 21 KB | `licenses/abrilfatface.txt` |
| BN Card 53 | DM Serif Display (`dmserif`) | SIL OFL 1.1 | Source | `ofl/dmserifdisplay` · [googlefonts/dm-fonts](https://github.com/googlefonts/dm-fonts) `027cea4e4f` | 400 25 KB | `licenses/dmserif.txt` |
| BN Card 54 | Pacifico (`pacifico`) | SIL OFL 1.1 | 없음 | `ofl/pacifico` · [googlefonts/Pacifico](https://github.com/googlefonts/Pacifico) `423e7cb2b0` | 400 45 KB | `licenses/pacifico.txt` |
| BN Card 55 | Lobster (`lobster`) | SIL OFL 1.1 | Lobster | `ofl/lobster` · [impallari/The-Lobster-Font](https://github.com/impallari/The-Lobster-Font) `0796aa8aa4` | 400 37 KB | `licenses/lobster.txt` |
| BN Card 56 | Dancing Script (`dancingscript`) | SIL OFL 1.1 | Dancing Script | `ofl/dancingscript` · [googlefonts/DancingScript](https://github.com/googlefonts/DancingScript) `291f87c052` | 400 38 KB · 700 38 KB | `licenses/dancingscript.txt` |
| BN Card 57 | Caveat (`caveat`) | SIL OFL 1.1 | 없음 | `ofl/caveat` · [googlefonts/caveat](https://github.com/googlefonts/caveat) `59745e818e` | 400 100 KB · 700 100 KB | `licenses/caveat.txt` |
| BN Card 58 | Permanent Marker (`permanentmarker`) | Apache 2.0 | 없음 | `apache/permanentmarker` · [googlefonts/permanentmarker](https://github.com/googlefonts/permanentmarker) `b0786e381d` | 400 39 KB | `licenses/permanentmarker.txt` |
| BN Card 59 | Shadows Into Light (`shadowsintolight`) | SIL OFL 1.1 | 없음 | `ofl/shadowsintolight` · [googlefonts/googlefontdirectory-hg](https://github.com/googlefonts/googlefontdirectory-hg) `52f780bc9d` | 400 27 KB | `licenses/shadowsintolight.txt` |
| BN Card 60 | Righteous (`righteous`) | SIL OFL 1.1 | Righteous | `ofl/righteous` · [librefonts/righteous](https://github.com/librefonts/righteous) `583c13c301` | 400 20 KB | `licenses/righteous.txt` |
| BN Card 61 | Bungee (`bungee`) | SIL OFL 1.1 | 없음 | `ofl/bungee` · [djrrb/Bungee](https://github.com/djrrb/Bungee) `eb03cf69ad` | 400 37 KB | `licenses/bungee.txt` |
| BN Card 62 | Monoton (`monoton`) | SIL OFL 1.1 | Monoton | `ofl/monoton` · [googlefonts/monoton](https://github.com/googlefonts/monoton) `db46b75aa0` | 400 26 KB | `licenses/monoton.txt` |
| BN Card 63 | Shrikhand (`shrikhand`) | SIL OFL 1.1 | 없음 | `ofl/shrikhand` · [jonpinhorn/shrikhand](https://github.com/jonpinhorn/shrikhand) `c11c9b0720` | 400 27 KB | `licenses/shrikhand.txt` |
| BN Card 64 | Orbitron (`orbitron`) | SIL OFL 1.1 | Orbitron | `ofl/orbitron` · [googlefonts/orbitron-vf](https://github.com/googlefonts/orbitron-vf) `f16482824e` | 400 18 KB · 700 18 KB | `licenses/orbitron.txt` |
| BN Card 65 | Press Start 2P (`pressstart`) | SIL OFL 1.1 | Press Start 2P | `ofl/pressstart2p` · [googlefonts/googlefontdirectory-hg](https://github.com/googlefonts/googlefontdirectory-hg) `52f780bc9d` | 400 11 KB | `licenses/pressstart.txt` |
| BN Card 66 | Silkscreen (`silkscreen`) | SIL OFL 1.1 | 없음 | `ofl/silkscreen` · [googlefonts/silkscreen](https://github.com/googlefonts/silkscreen) `206ccf3f52` | 400 9 KB · 700 9 KB | `licenses/silkscreen.txt` |
| BN Card 67 | VT323 (`vt323`) | SIL OFL 1.1 | 없음 | `ofl/vt323` · [phoikoi/VT323](https://github.com/phoikoi/VT323) `9bd4b3f698` | 400 32 KB | `licenses/vt323.txt` |
| BN Card 68 | Space Mono (`spacemono`) | SIL OFL 1.1 | 없음 | `ofl/spacemono` · [googlefonts/spacemono](https://github.com/googlefonts/spacemono) `329858c2c4` | 400 19 KB · 700 19 KB | `licenses/spacemono.txt` |
| BN Card 69 | Roboto Mono (`robotomono`) | SIL OFL 1.1 | 없음 | `ofl/robotomono` · [googlefonts/RobotoMono](https://github.com/googlefonts/RobotoMono) `111eb14e36` | 400 17 KB · 700 17 KB | `licenses/robotomono.txt` |
| BN Card 70 | JetBrains Mono (`jetbrainsmono`) | SIL OFL 1.1 | 없음 | `ofl/jetbrainsmono` · [JetBrains/JetBrainsMono](https://github.com/JetBrains/JetBrainsMono) `19371302b9` | 400 48 KB · 700 48 KB | `licenses/jetbrainsmono.txt` |

## 크기

- 모두 70개 글꼴, 파일 100개, 9.4 MB(원본 229 MB에서). 한글 글꼴 하나는 보통 굵기 50~480 KB, 영문 글꼴은 10~60 KB예요.
- 휴대폰은 글꼴마다 한 번 받아 장터 캐시에 두고 모든 카드가 같이 써요. 1.39 장터 작품 181개 가운데 151개가 공개 글꼴을 쓰고, 한 작품이 쓰는 글꼴은 보통 합쳐 0.3 MB(가장 많으면 1.2 MB), 테마 첫 화면(작품 12개)을 다 보면 글꼴 22개 3.7 MB쯤이에요. 안드로이드 '데이터 절약'을 켠 채 모바일 데이터면 받지 않고 휴대폰 글꼴로 그려요.
