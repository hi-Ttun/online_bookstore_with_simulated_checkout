-- Dữ liệu mẫu: 50 sách và tồn kho. Idempotent: chạy lại luôn cho cùng một trạng thái.
-- CẢNH BÁO: xóa toàn bộ đơn hàng, tồn kho và outbox. Chỉ dùng cho môi trường phát triển, demo và kiểm thử.
-- Kết quả kỳ vọng của bộ dữ liệu: docs/BACKEND.md, mục "Dữ liệu mẫu".

BEGIN;

TRUNCATE processed_event, inventory_movement, outbox_event, payment_attempt,
         order_line, orders, inventory, product
    RESTART IDENTITY CASCADE;

INSERT INTO product (id, sku, title, author, price_minor) VALUES
    ( 1, 'BOOK-001', 'Pride and Prejudice',                  'Jane Austen',           85000),
    ( 2, 'BOOK-002', 'Moby-Dick',                            'Herman Melville',       120000),
    ( 3, 'BOOK-003', 'Frankenstein',                         'Mary Shelley',          75000),
    ( 4, 'BOOK-004', 'The Adventures of Sherlock Holmes',    'Arthur Conan Doyle',    95000),
    ( 5, 'BOOK-005', 'Alice''s Adventures in Wonderland',    'Lewis Carroll',         60000),
    ( 6, 'BOOK-006', 'Great Expectations',                   'Charles Dickens',       110000),
    ( 7, 'BOOK-007', 'A Tale of Two Cities',                 'Charles Dickens',       105000),
    ( 8, 'BOOK-008', 'Oliver Twist',                         'Charles Dickens',       90000),
    ( 9, 'BOOK-009', 'Jane Eyre',                            'Charlotte Bronte',      98000),
    (10, 'BOOK-010', 'Wuthering Heights',                    'Emily Bronte',          88000),
    (11, 'BOOK-011', 'Dracula',                              'Bram Stoker',           92000),
    (12, 'BOOK-012', 'The Picture of Dorian Gray',           'Oscar Wilde',           70000),
    (13, 'BOOK-013', 'The Time Machine',                     'H. G. Wells',           55000),
    (14, 'BOOK-014', 'The War of the Worlds',                'H. G. Wells',           65000),
    (15, 'BOOK-015', 'Treasure Island',                      'Robert Louis Stevenson',72000),
    (16, 'BOOK-016', 'Strange Case of Dr Jekyll and Mr Hyde','Robert Louis Stevenson',50000),
    (17, 'BOOK-017', 'The Adventures of Tom Sawyer',         'Mark Twain',            78000),
    (18, 'BOOK-018', 'Adventures of Huckleberry Finn',       'Mark Twain',            82000),
    (19, 'BOOK-019', 'Little Women',                         'Louisa May Alcott',     99000),
    (20, 'BOOK-020', 'Emma',                                 'Jane Austen',           87000),
    (21, 'BOOK-021', 'Sense and Sensibility',                'Jane Austen',           84000),
    (22, 'BOOK-022', 'Persuasion',                           'Jane Austen',           76000),
    (23, 'BOOK-023', 'War and Peace',                        'Leo Tolstoy',           250000),
    (24, 'BOOK-024', 'Anna Karenina',                        'Leo Tolstoy',           180000),
    (25, 'BOOK-025', 'Crime and Punishment',                 'Fyodor Dostoevsky',     150000),
    (26, 'BOOK-026', 'The Brothers Karamazov',               'Fyodor Dostoevsky',     190000),
    (27, 'BOOK-027', 'Les Miserables',                       'Victor Hugo',           230000),
    (28, 'BOOK-028', 'The Hunchback of Notre-Dame',          'Victor Hugo',           125000),
    (29, 'BOOK-029', 'The Count of Monte Cristo',            'Alexandre Dumas',       210000),
    (30, 'BOOK-030', 'The Three Musketeers',                 'Alexandre Dumas',       135000),
    (31, 'BOOK-031', 'Twenty Thousand Leagues Under the Sea','Jules Verne',           89000),
    (32, 'BOOK-032', 'Around the World in Eighty Days',      'Jules Verne',           68000),
    (33, 'BOOK-033', 'Journey to the Center of the Earth',   'Jules Verne',           73000),
    (34, 'BOOK-034', 'Don Quixote',                          'Miguel de Cervantes',   220000),
    (35, 'BOOK-035', 'Madame Bovary',                        'Gustave Flaubert',      97000),
    (36, 'BOOK-036', 'The Odyssey',                          'Homer',                 115000),
    (37, 'BOOK-037', 'The Iliad',                            'Homer',                 118000),
    (38, 'BOOK-038', 'Meditations',                          'Marcus Aurelius',       58000),
    (39, 'BOOK-039', 'The Art of War',                       'Sun Tzu',               45000),
    (40, 'BOOK-040', 'The Prince',                           'Niccolo Machiavelli',   52000),
    (41, 'BOOK-041', 'Gulliver''s Travels',                  'Jonathan Swift',        86000),
    (42, 'BOOK-042', 'Robinson Crusoe',                      'Daniel Defoe',          83000),
    (43, 'BOOK-043', 'The Jungle Book',                      'Rudyard Kipling',       64000),
    (44, 'BOOK-044', 'Peter Pan',                            'J. M. Barrie',          57000),
    (45, 'BOOK-045', 'The Wonderful Wizard of Oz',           'L. Frank Baum',         62000),
    (46, 'BOOK-046', 'Anne of Green Gables',                 'L. M. Montgomery',      79000),
    (47, 'BOOK-047', 'The Secret Garden',                    'Frances Hodgson Burnett',71000),
    (48, 'BOOK-048', 'Heart of Darkness',                    'Joseph Conrad',         54000),
    (49, 'BOOK-049', 'The Call of the Wild',                 'Jack London',           59000),
    (50, 'BOOK-050', 'Walden',                               'Henry David Thoreau',   66000);

-- Đồng bộ sequence sau khi chèn id thủ công
SELECT setval('product_id_seq', (SELECT max(id) FROM product));

-- Tồn kho theo kịch bản kiểm thử: BOOK-001 = 1 (Test 1), 002 = 5 (Test 2), 003 = 3 (Test 3), 004 = 10 (timeout), còn lại 20
INSERT INTO inventory (product_id, on_hand, reserved)
SELECT id,
       CASE sku WHEN 'BOOK-001' THEN 1 WHEN 'BOOK-002' THEN 5 WHEN 'BOOK-003' THEN 3 WHEN 'BOOK-004' THEN 10 ELSE 20 END,
       0
FROM product;

INSERT INTO inventory_movement (product_id, kind, quantity)
SELECT product_id, 'SEED', on_hand FROM inventory;

COMMIT;

-- Kết quả mong đợi: 50 | 1 | 5 | 3 | 10 | 939
SELECT count(*)                                                         AS books,
       max(on_hand) FILTER (WHERE product_id = 1)                       AS book1,
       max(on_hand) FILTER (WHERE product_id = 2)                       AS book2,
       max(on_hand) FILTER (WHERE product_id = 3)                       AS book3,
       max(on_hand) FILTER (WHERE product_id = 4)                       AS book4,
       sum(on_hand)                                                     AS total_stock
FROM inventory;
