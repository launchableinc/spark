--- TESTS FOR DATETIME FORMATTING FUNCTIONS WITH INVALID PATTERNS ---

-- separating this from datetime-formatting.sql ,because the text form
-- for patterns with 5 letters in SimpleDateFormat varies from different JDKs
select date_format('2018-11-17 13:33:33.333', 'GGGGG');
-- pattern letter count can not be greater than 10
select date_format('2018-11-17 13:33:33.333', 'yyyyyyyyyyy');
select date_format('2018-11-17 13:33:33.333', 'YYYYYYYYYYY');
-- q/L in JDK 8 will fail when the count is more than 2
select date_format('2018-11-17 13:33:33.333', 'qqqqq');
select date_format('2018-11-17 13:33:33.333', 'QQQQQ');
select date_format('2018-11-17 13:33:33.333', 'MMMMM');
select date_format('2018-11-17 13:33:33.333', 'LLLLL');
select date_format('2018-11-17 13:33:33.333', 'www');
select date_format('2018-11-17 13:33:33.333', 'WW');
select date_format('2018-11-17 13:33:33.333', 'uuuuu');
select date_format('2018-11-17 13:33:33.333', 'EEEEE');
select date_format('2018-11-17 13:33:33.333', 'FF');
select date_format('2018-11-17 13:33:33.333', 'ddd');
-- DD is invalid in 8, but valid in 11 for the JDKs that PR builder uses
-- select date_format('2018-11-17 13:33:33.333', 'DD');
select date_format('2018-11-17 13:33:33.333', 'DDDD');
select date_format('2018-11-17 13:33:33.333', 'HHH');
select date_format('2018-11-17 13:33:33.333', 'hhh');
select date_format('2018-11-17 13:33:33.333', 'kkk');
select date_format('2018-11-17 13:33:33.333', 'KKK');
select date_format('2018-11-17 13:33:33.333', 'mmm');
select date_format('2018-11-17 13:33:33.333', 'sss');
select date_format('2018-11-17 13:33:33.333', 'SSSSSSSSSS');
select date_format('2018-11-17 13:33:33.333', 'aa');
select date_format('2018-11-17 13:33:33.333', 'V');
select date_format('2018-11-17 13:33:33.333', 'zzzzz');
select date_format('2018-11-17 13:33:33.333', 'XXXXXX');
select date_format('2018-11-17 13:33:33.333', 'ZZZZZZ');
select date_format('2018-11-17 13:33:33.333', 'OO');
select date_format('2018-11-17 13:33:33.333', 'xxxxxx');
