# Zmiany

Wygenerowano: 2026-09-14 12:05
Commit: 90b17df9 (develop)
Baza: upstream/master

## Nowości

- Widok tygodniowy planu lekcji — cały tydzień na jednym ekranie, tylko przedmiot, sala i godziny. Przełączasz go w menu planu lekcji, a bieżąca (albo najbliższa) lekcja jest wyróżniona.
- Usprawiedliwienia Librus — osobny panel oraz usprawiedliwianie pojedynczych lekcji z poziomu obecności, dla kont rodzica.
- Obecności pokazują status usprawiedliwienia: objęte wnioskiem nieobecności mają kolor usprawiedliwionych, a szczegóły prowadzą wprost do wniosku.
- Plan lekcji eksportowany do obrazka bierze kolory z aplikacji — każdy przedmiot swój, odwołania i zastępstwa w kolorach motywu.
- Aplikacja sama sprawdza aktualizacje i potrafi pobrać oraz zainstalować nową wersję.
- W „Pomoc i opinie” doszły pełne informacje o wersji (numer kompilacji, gałąź, commit, data, urządzenie) z przyciskiem kopiowania — przydatne przy zgłaszaniu błędów.

## Poprawki

- Powiadomienia push z Librusa wreszcie działają — rejestracja urządzenia nigdy wcześniej nie dochodziła do skutku.
- Odświeżanie synchronizuje wszystkie profile naraz, a powiadomienia dla rodzeństwa nie gubią się nawzajem; ta sama wiadomość dla dwóch profili przychodzi jako jedno powiadomienie.
- Wysłane usprawiedliwienie nie jest już raportowane jako błąd, mimo że Librus je zapisał.
- Zniknęły liczniki „nieprzeczytane”, które nie miały czego otworzyć.
- Dotknięcie tytułu na górnym pasku otwiera stronę główną, a napisu „X nieprzeczytane” — pierwszą nieprzeczytaną pozycję.
- Poprawiona synchronizacja w tle i podpowiedzi dotyczące oszczędzania baterii.
- Z rogu ekranu zniknął czerwony znacznik wersji.
